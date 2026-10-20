package com.hchat.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hchat.server.api.dto.ResourceDtos.CreateWebhook;
import com.hchat.server.api.dto.ResourceDtos.WebhookView;
import com.hchat.server.auth.Secrets;
import com.hchat.server.common.IdGenerator;
import com.hchat.server.common.Times;
import com.hchat.server.common.JsonCodec;
import com.hchat.server.config.HChatProperties;
import com.hchat.server.domain.WebhookDelivery;
import com.hchat.server.domain.WebhookEndpoint;
import com.hchat.server.mapper.WebhookDeliveryMapper;
import com.hchat.server.mapper.WebhookEndpointMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Webhook：HMAC-SHA256 签名 + 时间戳防重放 + 指数退避重试。
 *
 * <p>签名串为 {@code "<timestamp>.<rawBody>"}，Header 形如
 * {@code X-HChat-Signature: sha256=<hex>}，与 server-sdk-node 的验签实现保持一致。
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    public static final String HEADER_SIGNATURE = "X-HChat-Signature";
    public static final String HEADER_TIMESTAMP = "X-HChat-Timestamp";
    public static final String HEADER_EVENT = "X-HChat-Event";
    public static final String HEADER_DELIVERY = "X-HChat-Delivery";

    private final WebhookEndpointMapper endpointMapper;
    private final WebhookDeliveryMapper deliveryMapper;
    private final IdGenerator ids;
    private final JsonCodec json;
    private final HChatProperties props;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public WebhookService(WebhookEndpointMapper endpointMapper, WebhookDeliveryMapper deliveryMapper,
                          IdGenerator ids, JsonCodec json, HChatProperties props) {
        this.endpointMapper = endpointMapper;
        this.deliveryMapper = deliveryMapper;
        this.ids = ids;
        this.json = json;
        this.props = props;
    }

    public List<WebhookView> list(String tenantId, String appId) {
        return endpointMapper.selectList(new LambdaQueryWrapper<WebhookEndpoint>()
                        .eq(WebhookEndpoint::getTenantId, tenantId)
                        .eq(WebhookEndpoint::getAppId, appId)
                        .orderByDesc(WebhookEndpoint::getCreatedAt))
                .stream().map(this::toView).toList();
    }

    @Transactional
    public WebhookView create(String tenantId, String appId, CreateWebhook request) {
        WebhookEndpoint endpoint = new WebhookEndpoint();
        endpoint.setId(ids.nextId());
        endpoint.setTenantId(tenantId);
        endpoint.setAppId(appId);
        endpoint.setUrl(request.url());
        endpoint.setSecret(request.secret() == null || request.secret().isBlank()
                ? Secrets.randomWebhookSecret() : request.secret());
        endpoint.setEventsJson(json.write(request.events()));
        endpoint.setStatus("active");
        endpoint.setCreatedAt(Times.now());
        endpointMapper.insert(endpoint);
        return toView(endpoint);
    }

    /** 异步投递事件到所有订阅该事件的端点。 */
    @Async
    public void publish(String tenantId, String appId, String eventType, Map<String, Object> data) {
        List<WebhookEndpoint> endpoints = endpointMapper.selectList(new LambdaQueryWrapper<WebhookEndpoint>()
                .eq(WebhookEndpoint::getTenantId, tenantId)
                .eq(WebhookEndpoint::getAppId, appId)
                .eq(WebhookEndpoint::getStatus, "active"));
        if (endpoints.isEmpty()) {
            return;
        }
        for (WebhookEndpoint endpoint : endpoints) {
            if (!subscribes(endpoint, eventType)) {
                continue;
            }
            try {
                deliverWithRetry(endpoint, eventType, data);
            } catch (Exception e) {
                log.warn("Webhook 投递异常 endpoint={}: {}", endpoint.getId(), e.getMessage());
            }
        }
    }

    private boolean subscribes(WebhookEndpoint endpoint, String eventType) {
        Object parsed = json.readValue(endpoint.getEventsJson(), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        if (!(parsed instanceof List<?> list)) {
            return false;
        }
        return list.stream().anyMatch(e -> "*".equals(e) || eventType.equals(e));
    }

    private void deliverWithRetry(WebhookEndpoint endpoint, String eventType, Map<String, Object> data) {
        long deliveryId = ids.nextId();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", String.valueOf(deliveryId));
        envelope.put("type", eventType);
        envelope.put("createdAt", Times.now().toString());
        envelope.put("data", data);
        String body = json.write(envelope);

        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setId(deliveryId);
        delivery.setTenantId(endpoint.getTenantId());
        delivery.setAppId(endpoint.getAppId());
        delivery.setEndpointId(endpoint.getId());
        delivery.setEventType(eventType);
        delivery.setPayloadJson(body);
        delivery.setStatus("pending");
        delivery.setAttempts(0);
        delivery.setCreatedAt(Times.now());
        delivery.setUpdatedAt(Times.now());
        deliveryMapper.insert(delivery);

        int maxAttempts = Math.max(1, props.getWebhook().getMaxAttempts());
        long backoff = Math.max(100L, props.getWebhook().getInitialBackoffMs());
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String error = attemptDelivery(endpoint, deliveryId, eventType, body);
            delivery.setAttempts(attempt);
            delivery.setUpdatedAt(Times.now());
            if (error == null) {
                delivery.setStatus("success");
                delivery.setLastError(null);
                deliveryMapper.updateById(delivery);
                return;
            }
            delivery.setStatus("failed");
            delivery.setLastError(error.length() > 1000 ? error.substring(0, 1000) : error);
            deliveryMapper.updateById(delivery);
            if (attempt < maxAttempts) {
                try {
                    Thread.sleep(Math.min(backoff, 30_000L));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoff = backoff * 2;
            }
        }
    }

    /** @return null 表示成功，否则返回错误描述。 */
    private String attemptDelivery(WebhookEndpoint endpoint, long deliveryId, String eventType, String body) {
        long timestamp = Times.now().getEpochSecond();
        String signature = sign(endpoint.getSecret(), timestamp, body);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint.getUrl()))
                    .timeout(Duration.ofSeconds(Math.max(1, props.getWebhook().getTimeoutSeconds())))
                    .header("Content-Type", "application/json")
                    .header(HEADER_SIGNATURE, "sha256=" + signature)
                    .header(HEADER_TIMESTAMP, String.valueOf(timestamp))
                    .header(HEADER_EVENT, eventType)
                    .header(HEADER_DELIVERY, String.valueOf(deliveryId))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return null;
            }
            return "HTTP " + response.statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** HMAC-SHA256(secret, "{timestamp}.{body}") 的十六进制小写表示。 */
    public static String sign(String secret, long timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    private WebhookView toView(WebhookEndpoint endpoint) {
        Object events = json.readValue(endpoint.getEventsJson(), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        @SuppressWarnings("unchecked")
        List<String> eventList = events instanceof List ? (List<String>) events : List.of();
        return new WebhookView(String.valueOf(endpoint.getId()), endpoint.getUrl(), eventList,
                endpoint.getStatus(), endpoint.getCreatedAt() == null ? null : endpoint.getCreatedAt().toString());
    }
}
