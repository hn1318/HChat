package com.hchat.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hchat.server.auth.Secrets;
import com.hchat.server.common.Times;
import com.hchat.server.domain.App;
import com.hchat.server.domain.Message;
import com.hchat.server.mapper.AppMapper;
import com.hchat.server.mapper.MessageMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * REST API 集成测试（H2 + 完整 Spring 上下文）：
 * 覆盖建会话、消息幂等、未读、已读、撤回、错误码与多租户隔离。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ChatApiIntegrationTest {

    private static final String APP = "app_demo";
    private static final String TENANT_B_APP = "app_tenant_b_test";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    AppMapper appMapper;

    @Autowired
    MessageMapper messageMapper;

    private String token(String appId, String externalUserId, String displayName) {
        Map<String, Object> body = Map.of(
                "appId", appId,
                "externalUserId", externalUserId,
                "displayName", displayName);
        JsonNode node = post("/api/v1/auth/dev-token", null, body, HttpStatus.OK).getBody();
        return node.path("token").asText();
    }

    private ResponseEntity<JsonNode> post(String path, String token, Object body, HttpStatus expected) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        ResponseEntity<JsonNode> response = rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(expected);
        return response;
    }

    private ResponseEntity<JsonNode> get(String path, String token, HttpStatus expected) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        ResponseEntity<JsonNode> response = rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(expected);
        return response;
    }

    private JsonNode createConversation(String token, String dedupeKey) {
        Map<String, Object> body = new HashMap<>();
        body.put("type", "c2c");
        body.put("title", "测试会话");
        body.put("dedupeKey", dedupeKey);
        body.put("members", List.of(Map.of("externalUserId", "it_seller", "role", "seller")));
        return post("/api/v1/conversations", token, body, HttpStatus.OK).getBody();
    }

    @Test
    void buyerAndSellerCanChatEndToEnd() {
        String buyer = token(APP, "it_buyer", "买家");
        String seller = token(APP, "it_seller", "卖家");
        String dedupe = "it-case-" + UUID.randomUUID();
        JsonNode conversation = createConversation(buyer, dedupe);
        String conversationId = conversation.path("id").asText();
        assertThat(conversation.path("members")).hasSize(2);

        // dedupeKey 复用
        assertThat(createConversation(buyer, dedupe).path("id").asText()).isEqualTo(conversationId);

        // 幂等发送
        String clientMsgId = "it-" + UUID.randomUUID();
        JsonNode first = post("/api/v1/conversations/" + conversationId + "/messages", buyer,
                Map.of("clientMsgId", clientMsgId, "type", "text", "content", Map.of("text", "在吗？")),
                HttpStatus.OK).getBody();
        JsonNode second = post("/api/v1/conversations/" + conversationId + "/messages", buyer,
                Map.of("clientMsgId", clientMsgId, "type", "text", "content", Map.of("text", "在吗？")),
                HttpStatus.OK).getBody();
        assertThat(second.path("id").asText()).isEqualTo(first.path("id").asText());

        // 卖家未读 +1
        JsonNode sellerList = get("/api/v1/conversations", seller, HttpStatus.OK).getBody();
        JsonNode sellerConv = findById(sellerList.path("items"), conversationId);
        assertThat(sellerConv.path("unreadCount").asInt()).isEqualTo(1);
        assertThat(sellerConv.path("lastMessagePreview").asText()).isEqualTo("在吗？");

        // 已读后清零
        post("/api/v1/conversations/" + conversationId + "/read", seller,
                Map.of("messageId", first.path("id").asText()), HttpStatus.OK);
        JsonNode afterRead = findById(get("/api/v1/conversations", seller, HttpStatus.OK).getBody().path("items"),
                conversationId);
        assertThat(afterRead.path("unreadCount").asInt()).isZero();

        // 历史 + 撤回
        JsonNode page = get("/api/v1/conversations/" + conversationId + "/messages?limit=10", seller,
                HttpStatus.OK).getBody();
        assertThat(page.path("items")).hasSize(1);
        JsonNode recalled = post("/api/v1/messages/" + first.path("id").asText() + "/recall", buyer, null,
                HttpStatus.OK).getBody();
        assertThat(recalled.path("status").asText()).isEqualTo("recalled");
    }

    @Test
    void recallRejectsOtherUsersMessage() {
        String buyer = token(APP, "it_buyer2", "买家2");
        String seller = token(APP, "it_seller", "卖家");
        JsonNode conversation = createConversation(buyer, "it-recall-" + UUID.randomUUID());
        String conversationId = conversation.path("id").asText();
        JsonNode message = post("/api/v1/conversations/" + conversationId + "/messages", buyer,
                Map.of("type", "text", "content", Map.of("text", "这条不能被你撤回")), HttpStatus.OK).getBody();

        JsonNode error = post("/api/v1/messages/" + message.path("id").asText() + "/recall", seller, null,
                HttpStatus.FORBIDDEN).getBody();
        assertThat(error.path("error").path("code").asText()).isEqualTo("message_not_owned");
    }

    @Test
    void recallRejectsExpiredWindow() {
        String buyer = token(APP, "it_buyer3", "买家3");
        JsonNode conversation = createConversation(buyer, "it-expired-" + UUID.randomUUID());
        String conversationId = conversation.path("id").asText();
        JsonNode message = post("/api/v1/conversations/" + conversationId + "/messages", buyer,
                Map.of("type", "text", "content", Map.of("text", "很久以前的消息")), HttpStatus.OK).getBody();

        Message stored = messageMapper.selectById(Long.valueOf(message.path("id").asText()));
        stored.setCreatedAt(Times.now().minus(10, ChronoUnit.MINUTES));
        messageMapper.updateById(stored);

        JsonNode error = post("/api/v1/messages/" + stored.getId() + "/recall", buyer, null,
                HttpStatus.CONFLICT).getBody();
        assertThat(error.path("error").path("code").asText()).isEqualTo("recall_window_expired");
    }

    @Test
    void nonMemberCannotReadOrWrite() {
        String buyer = token(APP, "it_buyer4", "买家4");
        String outsider = token(APP, "it_outsider", "路人");
        String conversationId = createConversation(buyer, "it-outsider-" + UUID.randomUUID()).path("id").asText();

        JsonNode error = get("/api/v1/conversations/" + conversationId, outsider, HttpStatus.FORBIDDEN).getBody();
        assertThat(error.path("error").path("code").asText()).isEqualTo("not_a_member");

        post("/api/v1/conversations/" + conversationId + "/messages", outsider,
                Map.of("type", "text", "content", Map.of("text", "我不在这个会话")), HttpStatus.FORBIDDEN);
    }

    @Test
    void otherTenantCannotAccessConversation() {
        ensureTenantBApp();
        String buyer = token(APP, "it_buyer5", "买家5");
        String tenantB = token(TENANT_B_APP, "it_buyer5", "别家买家5");
        String conversationId = createConversation(buyer, "it-tenant-" + UUID.randomUUID()).path("id").asText();

        JsonNode error = get("/api/v1/conversations/" + conversationId, tenantB, HttpStatus.NOT_FOUND).getBody();
        assertThat(error.path("error").path("code").asText()).isEqualTo("conversation_not_found");
    }

    @Test
    void rejectsOversizedText() {
        String buyer = token(APP, "it_buyer6", "买家6");
        String conversationId = createConversation(buyer, "it-long-" + UUID.randomUUID()).path("id").asText();
        String longText = "x".repeat(5000);
        JsonNode error = post("/api/v1/conversations/" + conversationId + "/messages", buyer,
                Map.of("type", "text", "content", Map.of("text", longText)), HttpStatus.PAYLOAD_TOO_LARGE).getBody();
        assertThat(error.path("error").path("code").asText()).isEqualTo("payload_too_large");
    }

    @Test
    void invalidTokenReturnsUnifiedError() {
        JsonNode error = get("/api/v1/conversations", "bad-token", HttpStatus.UNAUTHORIZED).getBody();
        assertThat(error.path("error").path("code").asText()).isEqualTo("unauthorized");
        assertThat(error.path("error").path("requestId").asText()).isNotBlank();
    }

    @Test
    void cannotCreateConversationWithForeignAgent() {
        ensureTenantBApp();
        String tenantB = token(TENANT_B_APP, "it_agent_owner", "别家用户");
        JsonNode foreignAgent = post("/api/v1/ai/agents", tenantB,
                Map.of("name", "别家智能体", "systemPrompt", "x"), HttpStatus.OK).getBody();
        String buyer = token(APP, "it_buyer7", "买家7");

        post("/api/v1/conversations", buyer, Map.of(
                "type", "c2ai",
                "agentId", foreignAgent.path("id").asText()) , HttpStatus.NOT_FOUND);
    }

    private void ensureTenantBApp() {
        if (appMapper.selectById(TENANT_B_APP) != null) {
            return;
        }
        App app = new App();
        app.setId(TENANT_B_APP);
        app.setTenantId("tenant_b");
        app.setName("Tenant B");
        app.setAppSecretHash(Secrets.sha256Hex("tenant-b-secret"));
        app.setStatus("active");
        app.setCreatedAt(Instant.now());
        appMapper.insert(app);
    }

    private JsonNode findById(JsonNode items, String id) {
        for (JsonNode item : items) {
            if (id.equals(item.path("id").asText())) {
                return item;
            }
        }
        throw new AssertionError("未找到会话 " + id);
    }
}
