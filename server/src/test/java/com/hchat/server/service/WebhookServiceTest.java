package com.hchat.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hchat.server.common.IdGenerator;
import com.hchat.server.common.JsonCodec;
import com.hchat.server.config.HChatProperties;
import com.hchat.server.domain.WebhookDelivery;
import com.hchat.server.domain.WebhookEndpoint;
import com.hchat.server.mapper.WebhookDeliveryMapper;
import com.hchat.server.mapper.WebhookEndpointMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Webhook 签名与重试的单元测试（本地起 HttpServer 接收真实请求）。 */
class WebhookServiceTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void signatureMatchesReferenceVector() {
        // 参考向量由独立的 HMAC-SHA256 实现计算，用于跨语言一致性校验
        assertThat(WebhookService.sign("whsec_test", 1700000000L, "{\"hello\":\"world\"}"))
                .isEqualTo("f592bbf3951cfc94e560eecfb5d9dd4da6b0fff2e626235f8ab4b54860925d0b");
        assertThat(WebhookService.sign("whsec_test", 1700000001L, "{\"hello\":\"world\"}"))
                .isEqualTo("d3d5fca36b89cfaecd1cd49f22e3e66bf4cc0e45dc4e899eede091c8b9b06475");
        assertThat(WebhookService.sign("whsec_other", 1700000000L, "{\"hello\":\"world\"}"))
                .isNotEqualTo("f592bbf3951cfc94e560eecfb5d9dd4da6b0fff2e626235f8ab4b54860925d0b");
    }

    private record Received(String signature, String timestamp, String event, String body) {}

    @Test
    void retriesWithBackoffAndSignsEachAttempt() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<Received> received = new CopyOnWriteArrayList<>();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            int attempt = calls.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(
                    exchange.getRequestHeaders().getFirst("X-HChat-Signature"),
                    exchange.getRequestHeaders().getFirst("X-HChat-Timestamp"),
                    exchange.getRequestHeaders().getFirst("X-HChat-Event"),
                    body));
            exchange.sendResponseHeaders(attempt < 3 ? 500 : 200, -1);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();

        HChatProperties props = new HChatProperties();
        props.getWebhook().setMaxAttempts(3);
        props.getWebhook().setInitialBackoffMs(20);

        WebhookEndpointMapper endpointMapper = mock(WebhookEndpointMapper.class);
        WebhookDeliveryMapper deliveryMapper = mock(WebhookDeliveryMapper.class);
        WebhookEndpoint endpoint = new WebhookEndpoint();
        endpoint.setId(1L);
        endpoint.setTenantId("default");
        endpoint.setAppId("app_test");
        endpoint.setUrl("http://127.0.0.1:" + port + "/hook");
        endpoint.setSecret("whsec_test");
        endpoint.setEventsJson("[\"message.created\"]");
        endpoint.setStatus("active");
        when(endpointMapper.selectList(any())).thenReturn(List.of(endpoint));

        WebhookService service = new WebhookService(endpointMapper, deliveryMapper,
                new IdGenerator(1), new JsonCodec(new ObjectMapper()), props);
        service.publish("default", "app_test", "message.created", Map.of("messageId", "123"));

        assertThat(calls.get()).isEqualTo(3);
        for (Received item : received) {
            long timestamp = Long.parseLong(item.timestamp());
            assertThat(item.signature()).isEqualTo("sha256=" + WebhookService.sign("whsec_test", timestamp,
                    item.body()));
            assertThat(item.event()).isEqualTo("message.created");
            assertThat(item.body()).contains("\"type\":\"message.created\"", "messageId");
        }

        ArgumentCaptor<WebhookDelivery> captor = ArgumentCaptor.forClass(WebhookDelivery.class);
        org.mockito.Mockito.verify(deliveryMapper, org.mockito.Mockito.atLeastOnce()).updateById(captor.capture());
        WebhookDelivery last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(last.getStatus()).isEqualTo("success");
        assertThat(last.getAttempts()).isEqualTo(3);
    }

    @Test
    void ignoresEndpointsThatDoNotSubscribe() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        WebhookEndpointMapper endpointMapper = mock(WebhookEndpointMapper.class);
        WebhookEndpoint endpoint = new WebhookEndpoint();
        endpoint.setId(1L);
        endpoint.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
        endpoint.setSecret("whsec_test");
        endpoint.setEventsJson("[\"ai.completed\"]");
        endpoint.setStatus("active");
        when(endpointMapper.selectList(any())).thenReturn(List.of(endpoint));

        WebhookService service = new WebhookService(endpointMapper, mock(WebhookDeliveryMapper.class),
                new IdGenerator(1), new JsonCodec(new ObjectMapper()), new HChatProperties());
        service.publish("default", "app_test", "message.created", Map.of("x", 1));

        assertThat(calls.get()).isZero();
    }
}
