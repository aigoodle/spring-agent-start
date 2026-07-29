package io.github.aigoodle.workflow.node.builtin;

import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.node.ExecutionContext;
import io.github.aigoodle.workflow.node.NodeExecutor;
import io.github.aigoodle.workflow.node.NodeResult;
import io.github.aigoodle.workflow.variable.VariableResolver;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Full-fidelity HTTP node — the executor counterpart to the designer's
 * "HTTP 请求" card. Consumes the exact JSON the frontend
 * ({@code HttpNodeCard.vue}) persists:
 *
 * <pre>{@code
 * {
 *   "method": "GET|POST|PUT|DELETE|PATCH",
 *   "url": "https://... (templated)",
 *   "authorization": {
 *     "auth_type": "none|api_key",
 *     "api_key_header": "Authorization",
 *     "api_key_header_prefix": "basic|bearer|custom",
 *     "api_key_value": "..."
 *   },
 *   "headers":    [ {"name": "...", "value": "..."} ],
 *   "parameters": [ {"name": "...", "value": "..."} ],
 *   "bodyType":   "NONE|JSON|RAW|X_WWW_FORM_URLENCODED|FORM_DATA|BINARY",
 *   "body": {
 *     "JSON": "...",
 *     "RAW":  "...",
 *     "X_WWW_FORM_URLENCODED": [{name,value}],
 *     "FORM_DATA":             [{name,value,type: TEXT|FILE}],
 *     "BINARY": "..."
 *   },
 *   "timeoutSeconds": 30,
 *   "maxRetries":     0
 * }
 * }</pre>
 *
 * <h2>Retries</h2>
 * <p>{@code maxRetries} (default {@code 0}) controls how many times we re-send
 * the request after a transport-level failure — i.e. {@link IOException} from
 * {@link HttpClient#send} which covers connect refused, socket reset and the
 * per-request read timeout. HTTP responses with 4xx/5xx status codes are
 * <strong>not</strong> retried: they came back from the server, so the request
 * "worked" as far as this node is concerned; the caller decides what to do
 * with the status. Retries use a short fixed backoff (200ms) — no exponential
 * ramp, we're guarding against transient blips, not overload.</p>
 *
 * <h2>Legacy inputs</h2>
 * <ul>
 *   <li>{@code headers} may be a {@code Map<String, String>} — programmatic
 *       callers and the pre-designer test fixtures used that shape. Both are
 *       accepted so we don't break existing graphs / tests.</li>
 *   <li>Older graphs where {@code body} is a bare string are still sent
 *       verbatim (no automatic Content-Type).</li>
 * </ul>
 *
 * <h2>Outputs</h2>
 * <ul>
 *   <li>{@code status} — HTTP status code (int)</li>
 *   <li>{@code body} — response body (string)</li>
 *   <li>{@code headers} — response headers ({@code Map<String, List<String>>})</li>
 * </ul>
 *
 * <p>All string fields (URL, header names/values, parameter names/values,
 * body content) run through {@link VariableResolver#render} so
 * {@code {{#node.field#}}} references resolve against the workflow variable
 * pool.</p>
 */
public class HttpRequestNodeExecutor implements NodeExecutor {

    private static final Set<String> ALLOWED_METHODS =
            Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");

    private static final String DEFAULT_USER_AGENT = "spring-agent-start-workflow/1.0";

    /** Hard ceiling on {@code maxRetries} to keep pathological configs from
     *  hammering a downed endpoint for minutes. */
    private static final int MAX_RETRY_CEILING = 10;

    /** Fixed backoff between attempts. Short on purpose — we're only papering
     *  over transient network blips, not orchestrating a real retry policy. */
    private static final long RETRY_BACKOFF_MS = 200L;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * Base URL prepended to node URLs that aren't absolute. Empty string
     * disables the feature — designers must then supply a fully-qualified URL.
     * Trailing slashes are stripped so we can join with a leading slash cleanly.
     */
    private final String baseUrl;

    /** Zero-arg for programmatic use / tests where no base URL is needed. */
    public HttpRequestNodeExecutor() {
        this("");
    }

    public HttpRequestNodeExecutor(String baseUrl) {
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
        return NodeType.HTTP_REQUEST;
    }

    @Override
    public NodeResult execute(NodeDef node, ExecutionContext ctx) {
        String method = node.getString("method", "GET").trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_METHODS.contains(method)) {
            return NodeResult.failure("Unsupported HTTP method: " + method);
        }

        String rawUrl = VariableResolver.render(node.getString("url", ""), ctx.getPool());
        if (rawUrl == null || rawUrl.isBlank()) {
            return NodeResult.failure("HTTP node URL is empty");
        }
        String resolvedUrl = resolveAgainstBase(rawUrl);
        if (resolvedUrl == null) {
            return NodeResult.failure("HTTP node URL '" + rawUrl
                    + "' is not absolute and no agent-boot.http-base-url is configured");
        }
        String finalUrl = appendQueryString(resolvedUrl, node.getMapList("parameters"), ctx);

        URI uri;
        try {
            uri = URI.create(finalUrl);
        } catch (IllegalArgumentException e) {
            return NodeResult.failure("Invalid URL: " + finalUrl);
        }

        int timeout = Math.max(1, node.getInt("timeoutSeconds", 30));
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(timeout));

        // Track headers the user set so body-type Content-Type defaults don't
        // clobber explicit overrides.
        Set<String> userHeaders = applyHeaders(builder, node.get("headers"), ctx);
        applyAuthorization(builder, node.get("authorization"), ctx, userHeaders);

        BodyPart bodyPart = buildBody(node, ctx, method);
        if (bodyPart.failure != null) {
            return NodeResult.failure(bodyPart.failure);
        }
        if (bodyPart.contentType != null && !containsHeader(userHeaders, "Content-Type")) {
            builder.header("Content-Type", bodyPart.contentType);
        }
        if (!containsHeader(userHeaders, "User-Agent")) {
            builder.header("User-Agent", DEFAULT_USER_AGENT);
        }
        builder.method(method, bodyPart.publisher);

        int maxRetries = Math.min(MAX_RETRY_CEILING, Math.max(0, node.getInt("maxRetries", 0)));
        HttpRequest request = builder.build();
        IOException lastFailure = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
                // Any HTTP response — including 4xx/5xx — is a successful send.
                // The caller inspects `status` and decides. We do NOT retry on
                // error codes: that's application-level, not transport.
                return NodeResult.empty()
                        .output("status", resp.statusCode())
                        .output("body", resp.body())
                        .output("headers", resp.headers().map());
            } catch (IOException e) {
                // Transport failure — connection refused, socket reset,
                // read timeout (HttpTimeoutException extends IOException).
                // Retry-eligible.
                lastFailure = e;
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(RETRY_BACKOFF_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return NodeResult.failure("HTTP request to " + finalUrl
                                + " interrupted during retry backoff");
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return NodeResult.failure("HTTP request to " + finalUrl + " was interrupted");
            }
        }
        String suffix = maxRetries > 0 ? " (after " + (maxRetries + 1) + " attempts)" : "";
        return NodeResult.failure("HTTP request to " + finalUrl + " failed"
                + suffix + ": " + (lastFailure == null ? "unknown error" : lastFailure.getMessage()));
    }

    // -------- helpers --------

    /**
     * Joins a node-configured URL with the base URL when the former isn't
     * absolute. Rules:
     * <ul>
     *   <li>Starts with {@code http://} or {@code https://} (case-insensitive)
     *       → returned as-is.</li>
     *   <li>Otherwise → {@code baseUrl + "/" + trimmedRelative}. Leading and
     *       trailing slashes on either side are collapsed so we never emit
     *       double slashes.</li>
     *   <li>Base URL empty + relative URL → returns {@code null} so the caller
     *       can surface a clear config error rather than sending a malformed
     *       request.</li>
     * </ul>
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

    /** Renders a name/value list into the query string and appends to url. */
    private String appendQueryString(String url, List<Map<String, Object>> params, ExecutionContext ctx) {
        if (params == null || params.isEmpty()) {
            return url;
        }
        List<String> pairs = new ArrayList<>(params.size());
        for (Map<String, Object> entry : params) {
            String name = str(entry.get("name"));
            if (name == null || name.isBlank()) {
                continue;
            }
            String value = VariableResolver.render(str(entry.get("value")), ctx.getPool());
            pairs.add(URLEncoder.encode(name.trim(), StandardCharsets.UTF_8)
                    + "=" + URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8));
        }
        if (pairs.isEmpty()) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + String.join("&", pairs);
    }

    /**
     * Copies user-configured headers to the request. Accepts the designer's
     * {@code [{name,value}]} shape and the legacy {@code Map<String,String>}
     * shape. Returns the header names actually set so body helpers can avoid
     * overwriting explicit overrides.
     */
    @SuppressWarnings("unchecked")
    private Set<String> applyHeaders(HttpRequest.Builder builder, Object headers, ExecutionContext ctx) {
        Set<String> set = new HashSet<>();
        if (headers instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> row)) continue;
                String name = str(row.get("name"));
                if (name == null || name.isBlank()) continue;
                String value = VariableResolver.render(str(row.get("value")), ctx.getPool());
                builder.header(name.trim(), value == null ? "" : value);
                set.add(name.trim().toLowerCase(Locale.ROOT));
            }
        } else if (headers instanceof Map<?, ?> map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) map).entrySet()) {
                if (e.getKey() == null || e.getKey().isBlank()) continue;
                String rendered = VariableResolver.render(str(e.getValue()), ctx.getPool());
                builder.header(e.getKey(), rendered == null ? "" : rendered);
                set.add(e.getKey().toLowerCase(Locale.ROOT));
            }
        }
        return set;
    }

    /**
     * Injects the API-key header from the {@code authorization} block. Supports
     * three prefixes matching the designer's Authorization card: {@code basic},
     * {@code bearer}, {@code custom} (no prefix). No-op when auth_type is
     * {@code none} or when the value is empty.
     */
    private void applyAuthorization(HttpRequest.Builder builder, Object auth,
                                    ExecutionContext ctx, Set<String> userHeaders) {
        if (!(auth instanceof Map<?, ?> map)) return;
        String authType = str(map.get("auth_type"));
        if (authType == null || "none".equalsIgnoreCase(authType)) return;
        if (!"api_key".equalsIgnoreCase(authType)) return;

        String value = VariableResolver.render(str(map.get("api_key_value")), ctx.getPool());
        if (value == null || value.isBlank()) return;

        String header = str(map.get("api_key_header"));
        if (header == null || header.isBlank()) header = "Authorization";

        String prefix = str(map.get("api_key_header_prefix"));
        String composed;
        if ("bearer".equalsIgnoreCase(prefix)) {
            composed = "Bearer " + value;
        } else if ("basic".equalsIgnoreCase(prefix)) {
            composed = "Basic " + value;
        } else {
            composed = value;
        }
        builder.header(header.trim(), composed);
        userHeaders.add(header.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Resolves the body payload based on {@code bodyType}. Returns a
     * {@link BodyPart} carrying the publisher, the Content-Type we'd like to
     * default (may be null), and an optional failure message.
     */
    private BodyPart buildBody(NodeDef node, ExecutionContext ctx, String method) {
        // GET/HEAD/DELETE-with-no-body remain valid — the designer just leaves
        // bodyType at NONE. But we don't force it; the user can send a body
        // with GET if they really want to.
        String bodyType = node.getString("bodyType", "NONE").trim().toUpperCase(Locale.ROOT);
        Object bodyObj = node.get("body");

        // Legacy: body is a raw string → send verbatim, no Content-Type default.
        if (bodyObj instanceof String s) {
            String rendered = VariableResolver.render(s, ctx.getPool());
            return BodyPart.of(publish(rendered), null);
        }

        Map<?, ?> bodyMap = bodyObj instanceof Map<?, ?> m ? m : Map.of();

        switch (bodyType) {
            case "NONE":
                return BodyPart.empty();

            case "JSON": {
                String json = VariableResolver.render(str(bodyMap.get("JSON")), ctx.getPool());
                return BodyPart.of(publish(json), "application/json; charset=utf-8");
            }

            case "RAW": {
                String raw = VariableResolver.render(str(bodyMap.get("RAW")), ctx.getPool());
                return BodyPart.of(publish(raw), "text/plain; charset=utf-8");
            }

            case "X_WWW_FORM_URLENCODED": {
                String encoded = encodeForm(asMapList(bodyMap.get("X_WWW_FORM_URLENCODED")), ctx);
                return BodyPart.of(publish(encoded), "application/x-www-form-urlencoded; charset=utf-8");
            }

            case "FORM_DATA": {
                MultipartResult mp = buildMultipart(asMapList(bodyMap.get("FORM_DATA")), ctx);
                if (mp.failure != null) return BodyPart.failure(mp.failure);
                return BodyPart.of(HttpRequest.BodyPublishers.ofByteArray(mp.bytes),
                        "multipart/form-data; boundary=" + mp.boundary);
            }

            case "BINARY": {
                String bin = VariableResolver.render(str(bodyMap.get("BINARY")), ctx.getPool());
                return BodyPart.of(publish(bin), "application/octet-stream");
            }

            default:
                return BodyPart.failure("Unknown bodyType: " + bodyType);
        }
    }

    private static BodyPublisher publish(String s) {
        if (s == null || s.isEmpty()) return HttpRequest.BodyPublishers.noBody();
        return HttpRequest.BodyPublishers.ofString(s, StandardCharsets.UTF_8);
    }

    private String encodeForm(List<Map<String, Object>> rows, ExecutionContext ctx) {
        if (rows == null || rows.isEmpty()) return "";
        List<String> pairs = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            String name = str(row.get("name"));
            if (name == null || name.isBlank()) continue;
            String value = VariableResolver.render(str(row.get("value")), ctx.getPool());
            pairs.add(URLEncoder.encode(name.trim(), StandardCharsets.UTF_8)
                    + "=" + URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8));
        }
        return String.join("&", pairs);
    }

    /**
     * Builds a minimal multipart/form-data payload from the FORM_DATA rows.
     * TEXT rows are emitted as string parts; FILE rows currently fail loudly —
     * the workflow variable pool doesn't carry file bytes yet, so silently
     * dropping the row would be worse than surfacing "not supported".
     */
    private MultipartResult buildMultipart(List<Map<String, Object>> rows, ExecutionContext ctx) {
        String boundary = "----spring-agent-start-boundary-" + Long.toHexString(System.nanoTime());
        StringBuilder sb = new StringBuilder();
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                String name = str(row.get("name"));
                if (name == null || name.isBlank()) continue;
                String rowType = str(row.get("type"));
                if (rowType != null && "FILE".equalsIgnoreCase(rowType)) {
                    return MultipartResult.failure(
                            "FORM_DATA file uploads are not supported yet for field '" + name + "'");
                }
                String value = VariableResolver.render(str(row.get("value")), ctx.getPool());
                sb.append("--").append(boundary).append("\r\n");
                sb.append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n");
                sb.append(value == null ? "" : value).append("\r\n");
            }
        }
        sb.append("--").append(boundary).append("--\r\n");
        return MultipartResult.ok(boundary, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static boolean containsHeader(Set<String> lowerCased, String name) {
        return lowerCased.contains(name.toLowerCase(Locale.ROOT));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asMapList(Object v) {
        return v instanceof List ? (List<Map<String, Object>>) v : List.of();
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    // -------- carrier types --------

    private static final class BodyPart {
        final BodyPublisher publisher;
        final String contentType;
        final String failure;

        private BodyPart(BodyPublisher publisher, String contentType, String failure) {
            this.publisher = publisher;
            this.contentType = contentType;
            this.failure = failure;
        }

        static BodyPart of(BodyPublisher p, String ct) { return new BodyPart(p, ct, null); }
        static BodyPart empty() { return new BodyPart(HttpRequest.BodyPublishers.noBody(), null, null); }
        static BodyPart failure(String err) { return new BodyPart(null, null, err); }
    }

    private static final class MultipartResult {
        final String boundary;
        final byte[] bytes;
        final String failure;

        private MultipartResult(String boundary, byte[] bytes, String failure) {
            this.boundary = boundary;
            this.bytes = bytes;
            this.failure = failure;
        }

        static MultipartResult ok(String b, byte[] bytes) { return new MultipartResult(b, bytes, null); }
        static MultipartResult failure(String err) { return new MultipartResult(null, null, err); }
    }
}
