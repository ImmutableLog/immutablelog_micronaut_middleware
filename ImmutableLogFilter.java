import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.http.*;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.*;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;

import java.net.URI;
import java.net.http.*;
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

            Map<String, Object> meta = Map.of(
                "type", type,
                "event_name", eventName,
                "service", serviceName,
                "request_id", requestId,
                "env", env
            );

            Map<String, Object> bodyMap = Map.of(
                "payload", mapper.writeValueAsString(payloadMap),
                "meta", meta
            );

            String json = mapper.writeValueAsString(bodyMap);

            var httpReq = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + "/v1/events"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("Idempotency-Key", requestId)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

            http.sendAsync(httpReq, HttpResponse.BodyHandlers.discarding());

        } catch (Exception ignored) {}
    }

    private String getClientIp(HttpRequest<?> request) {
        String xff = request.getHeaders().get("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        String xri = request.getHeaders().get("X-Real-IP");
        if (xri != null && !xri.isBlank()) return xri;
        return request.getRemoteAddress().getAddress().getHostAddress();
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
