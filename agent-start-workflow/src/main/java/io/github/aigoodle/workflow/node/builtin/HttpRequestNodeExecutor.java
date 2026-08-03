package io.github.aigoodle.workflow.node.builtin;

import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.node.ExecutionContext;
import io.github.aigoodle.workflow.node.NodeExecutor;
import io.github.aigoodle.workflow.node.NodeResult;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Sends a workflow HTTP request and maps the transport outcome to node outputs.
 * Configuration parsing is delegated to {@link HttpNodeRequestFactory}.
 */
public class HttpRequestNodeExecutor implements NodeExecutor {

    private static final long RETRY_BACKOFF_MILLIS = 200L;

    private final HttpClient httpClient;
    private final HttpNodeRequestFactory requestFactory;
    private final RetryBackoff retryBackoff;

    public HttpRequestNodeExecutor() {
        this("");
    }

    public HttpRequestNodeExecutor(String baseUrl) {
        this(defaultHttpClient(), new HttpNodeRequestFactory(baseUrl), fixedRetryBackoff());
    }

    HttpRequestNodeExecutor(HttpClient httpClient, HttpNodeRequestFactory requestFactory,
                            RetryBackoff retryBackoff) {
        this.httpClient = httpClient;
        this.requestFactory = requestFactory;
        this.retryBackoff = retryBackoff;
    }

    @Override
    public NodeType type() {
        return NodeType.HTTP_REQUEST;
    }

    @Override
    public NodeResult execute(NodeDef node, ExecutionContext context) {
        HttpNodeRequestFactory.PreparedRequest preparedRequest =
                requestFactory.prepare(node, context);
        if (preparedRequest.failed()) {
            return NodeResult.failure(preparedRequest.error());
        }
        return send(preparedRequest);
    }

    private NodeResult send(HttpNodeRequestFactory.PreparedRequest preparedRequest) {
        IOException lastTransportFailure = null;
        for (int attempt = 0; attempt <= preparedRequest.retryCount(); attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(
                        preparedRequest.request(), HttpResponse.BodyHandlers.ofString());
                return responseResult(response);
            } catch (IOException transportFailure) {
                lastTransportFailure = transportFailure;
                if (attempt < preparedRequest.retryCount()) {
                    try {
                        retryBackoff.pause();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return NodeResult.failure("HTTP request to " + preparedRequest.url()
                                + " interrupted during retry backoff");
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return NodeResult.failure(
                        "HTTP request to " + preparedRequest.url() + " was interrupted");
            }
        }
        return exhaustedRetries(preparedRequest, lastTransportFailure);
    }

    private static NodeResult responseResult(HttpResponse<String> response) {
        // 4xx/5xx are valid HTTP responses; downstream nodes decide how to handle them.
        return NodeResult.empty()
                .output("status", response.statusCode())
                .output("body", response.body())
                .output("headers", response.headers().map());
    }

    private static NodeResult exhaustedRetries(
            HttpNodeRequestFactory.PreparedRequest request, IOException lastFailure) {
        String attemptDescription = request.retryCount() > 0
                ? " (after " + (request.retryCount() + 1) + " attempts)"
                : "";
        String failureReason = lastFailure == null ? "unknown error" : lastFailure.getMessage();
        return NodeResult.failure("HTTP request to " + request.url() + " failed"
                + attemptDescription + ": " + failureReason);
    }

    private static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    private static RetryBackoff fixedRetryBackoff() {
        return () -> Thread.sleep(RETRY_BACKOFF_MILLIS);
    }

    @FunctionalInterface
    interface RetryBackoff {
        void pause() throws InterruptedException;
    }
}
