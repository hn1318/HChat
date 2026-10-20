package com.hchat.server.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

/** WebSocket 协议集成测试：首帧 auth、消息广播、已读回执。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WebSocketRealtimeTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    private static final class Collector implements WebSocket.Listener {
        private final BlockingQueue<JsonNode> frames = new LinkedBlockingQueue<>();
        private final ObjectMapper mapper;
        private final StringBuilder buffer = new StringBuilder();

        Collector(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                try {
                    frames.add(mapper.readTree(buffer.toString()));
                } catch (Exception ignored) {
                    // 忽略非 JSON 帧
                }
                buffer.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        JsonNode await(String type, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                JsonNode frame = frames.poll(500, TimeUnit.MILLISECONDS);
                if (frame != null && type.equals(frame.path("type").asText())) {
                    return frame;
                }
            }
            throw new AssertionError("等待 WS 事件超时: " + type);
        }

        void send(WebSocket socket, Object frame) {
            try {
                socket.sendText(mapper.writeValueAsString(frame), true).join();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private String devToken(String externalUserId, String displayName) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"appId\":\"app_demo\",\"externalUserId\":\"" + externalUserId
                + "\",\"displayName\":\"" + displayName + "\"}";
        String json = rest.exchange("/api/v1/auth/dev-token", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class).getBody();
        try {
            return mapper.readTree(json).path("token").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String createConversation(String token, String dedupeKey, String peerExternalId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        String body = """
                {"type":"c2c","title":"WS 测试","dedupeKey":"%s",
                 "members":[{"externalUserId":"%s","role":"seller"}]}
                """.formatted(dedupeKey, peerExternalId);
        String json = rest.exchange("/api/v1/conversations", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class).getBody();
        try {
            return mapper.readTree(json).path("id").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void broadcastsMessagesAndReadReceipts() throws Exception {
        String sellerToken = devToken("ws_seller", "卖家");
        String buyerToken = devToken("ws_buyer", "买家");
        String conversationId = createConversation(buyerToken, "ws-" + System.nanoTime(), "ws_seller");

        HttpClient client = HttpClient.newHttpClient();
        Collector buyer = new Collector(mapper);
        Collector seller = new Collector(mapper);
        WebSocket buyerWs = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), buyer).join();
        WebSocket sellerWs = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), seller).join();

        buyer.send(buyerWs, Map.of("version", 1, "type", "auth", "payload", Map.of("token", buyerToken)));
        seller.send(sellerWs, Map.of("version", 1, "type", "auth", "payload", Map.of("token", sellerToken)));
        assertThat(buyer.await("auth.ok", Duration.ofSeconds(5)).path("payload").path("userId").asText())
                .isNotBlank();
        seller.await("auth.ok", Duration.ofSeconds(5));

        // 未认证前发消息必须被拒绝 —— 用第三个连接验证
        Collector anonymous = new Collector(mapper);
        WebSocket anonWs = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), anonymous).join();
        anonymous.send(anonWs, Map.of("version", 1, "type", "message.send",
                "payload", Map.of("conversationId", conversationId, "type", "text",
                        "content", Map.of("text", "hack"))));
        assertThat(anonymous.await("error", Duration.ofSeconds(5)).path("payload").path("code").asText())
                .isEqualTo("unauthorized");
        anonWs.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();

        // 正常发送 → 双方都收到
        buyer.send(buyerWs, Map.of("version", 1, "type", "message.send",
                "payload", Map.of("conversationId", conversationId, "clientMsgId", "ws-1",
                        "type", "text", "content", Map.of("text", "实时消息"))));
        JsonNode sellerEvent = seller.await("message.new", Duration.ofSeconds(5));
        assertThat(sellerEvent.path("payload").path("message").path("content").path("text").asText())
                .isEqualTo("实时消息");
        JsonNode buyerEvent = buyer.await("message.new", Duration.ofSeconds(5));
        String messageId = buyerEvent.path("payload").path("message").path("id").asText();

        // 卖家已读 → 买家收到 message.read
        seller.send(sellerWs, Map.of("version", 1, "type", "message.read",
                "payload", Map.of("conversationId", conversationId, "messageId", messageId)));
        JsonNode readEvent = buyer.await("message.read", Duration.ofSeconds(5));
        assertThat(readEvent.path("payload").path("messageId").asText()).isEqualTo(messageId);

        // 心跳
        buyer.send(buyerWs, Map.of("version", 1, "type", "ping", "payload", Map.of()));
        buyer.await("pong", Duration.ofSeconds(5));

        buyerWs.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
        sellerWs.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
    }
}
