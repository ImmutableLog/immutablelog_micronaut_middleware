import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.http.*;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.*;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;

import java.net.URI;
import java.net.http.HttpClient;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Filter("/**")
@Singleton
public class ImmutableLogFilter implements HttpServerFilter {

    @Value("${immutablelog.api-key}")
    String apiKey;

    @Value("${immutablelog.service-name:my-service}")
    String serviceName;

    @Value("${immutablelog.env:production}")
    String env;

    @Value("${immutablelog.url:https://api.immutablelog.com}")
    String apiUrl;

    private static final Set<String> SKIP_PATHS = Set.of("/health", "/ping");
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @Override
    public int getOrder() {
        return ServerFilterPhase.FIRST.order();
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(
        HttpRequest<?> request,
        ServerFilterChain chain
    ) {
        String path = request.getPath();

        if (SKIP_PATHS.stream().anyMatch(path::startsWith)) {
            return chain.proceed(request);
        }

        long startedAt = System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString();

        return Publishers.map(chain.proceed(request), response -> {
            long latencyMs = System.currentTimeMillis() - startedAt;
            emit(request, response, requestId, latencyMs, null);
            return response;
        });
    }

    public void emitError(HttpRequest<?> request, String requestId,
                          long latencyMs, Throwable ex) {
        emit(request, null, requestId, latencyMs, ex);
    }

    private void emit(HttpRequest<?> request, MutableHttpResponse<?> response,
                      String requestId, long latencyMs, Throwable ex) {
        try {
            String method = request.getMethodName();
            String path = request.getPath();
            int status = response != null ? response.getStatus().getCode() : 500;

            // Custom event name via request attribute
            String customEvent = request.getAttribute("imtbl.eventName", String.class).orElse(null);
            String eventName = customEvent != null
                ? customEvent
                : method.toLowerCase() + "." + path.replace("/", ".").replaceAll("^\\.+", "");

            String type = status >= 500 ? "error"
                : status >= 400 ? "error"
                : status >= 300 ? "info"
                : "success";

            Map<String, Object> payloadMap = new LinkedHashMap<>();
            payloadMap.put("method", method);
            payloadMap.put("path", path);
            payloadMap.put("status", status);
            payloadMap.put("latency_ms", latencyMs);
            payloadMap.put("client_ip", getClientIp(request));
            payloadMap.put("user_agent", request.getHeaders().get("User-Agent"));

            request.getBody(byte[].class).ifPresent(body -> {
                if (body.length > 0) {
                    payloadMap.put("request_body_hash", sha256Hex(body));
                }
            });

            if (ex != null) {
                Map<String, Object> errMap = new LinkedHashMap<>();
                errMap.put("type", ex.getClass().getName());
                errMap.put("message", ex.getMessage());
                errMap.put("retryable", !(ex instanceof IllegalArgumentException));
                payloadMap.put("error", errMap);
            }

            // Besides the middleware's own keys, this carries the reserved observability keys
            // of the contract (service, environment, client_ip, http_method, http_status,
            // http_route, log_level, error_type, error_message). All of them are strings:
            // the core only accepts meta as a string -> string map.
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("type", type);
            meta.put("event_name", eventName);
            meta.put("service", serviceName);
            meta.put("request_id", requestId);
            // "env" is the legacy name, kept because there may be clients in production
            // reading it; "environment" is the canonical name of the contract. Same value.
            meta.put("env", env);
            meta.put("environment", env);
            meta.put("http_method", method);
            meta.put("http_status", String.valueOf(status));
            meta.put("http_route", getHttpRoute(request, path));
            meta.put("log_level", logLevelFrom(type));

            // client_ip has to travel in meta (not only in the payload) so the platform can
            // promote it to source.ip; "unknown" is not an IP, so it is not worth sending.
            String clientIp = getClientIp(request);
            if (clientIp != null && !clientIp.isBlank() && !clientIp.equals("unknown")) {
                meta.put("client_ip", clientIp);
            }

            if (ex != null) {
                meta.put("error_type", ex.getClass().getName());
                String errMessage = ex.getMessage();
                if (errMessage != null && !errMessage.isBlank()) {
                    meta.put("error_message", truncate(errMessage, 500));
                }
            }

            Map<String, Object> bodyMap = Map.of(
                "payload", mapper.writeValueAsString(payloadMap),
                "meta", meta
            );

            String json = mapper.writeValueAsString(bodyMap);

            var httpReq = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + "/v1/events"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("Idempotency-Key", requestId)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json))
                .build();

            http.sendAsync(httpReq, java.net.http.HttpResponse.BodyHandlers.discarding());

        } catch (Exception ignored) {}
    }

    /**
     * Route for meta.http_route. Prefers the URI template resolved by the router
     * (e.g. "/orders/{id}") over the concrete path (e.g. "/orders/42"): the concrete
     * path carries the id and blows up the cardinality of the field on the platform
     * side. The attribute is set by the router before the route runs, so it is already
     * there when this filter maps the response; falls back to the path when no route
     * matched (e.g. a 404).
     */
    private String getHttpRoute(HttpRequest<?> request, String path) {
        String template = request.getAttribute(HttpAttributes.URI_TEMPLATE, String.class).orElse(null);
        if (template != null && !template.isBlank()) {
            return template.startsWith("/") ? template : "/" + template;
        }
        return path;
    }

    /** Maps the event type to meta.log_level (ECS log.level). */
    private String logLevelFrom(String kind) {
        return "error".equals(kind) ? "error"
            : "warning".equals(kind) ? "warn"
            : "debug".equals(kind) ? "debug"
            : "info"; // info, success, audit
    }

    private String truncate(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }

    private String getClientIp(HttpRequest<?> request) {
        String xff = request.getHeaders().get("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        String xri = request.getHeaders().get("X-Real-IP");
        if (xri != null && !xri.isBlank()) return xri;
        // Without a resolved remote address (null or unresolved InetSocketAddress) there is
        // no IP to send: return null instead of throwing, otherwise the whole event is lost.
        var remote = request.getRemoteAddress();
        if (remote == null || remote.getAddress() == null) return null;
        return remote.getAddress().getHostAddress();
    }

    private String sha256Hex(byte[] data) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "hash-error";
        }
    }
}
