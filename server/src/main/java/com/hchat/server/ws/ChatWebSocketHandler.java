package com.hchat.server.ws;

import com.hchat.server.common.Times;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hchat.server.api.dto.MessageDtos;
import com.hchat.server.auth.ChatPrincipal;
import com.hchat.server.auth.JwtService;
import com.hchat.server.common.ApiException;
import com.hchat.server.common.ErrorCode;
import com.hchat.server.domain.Conversation;
import com.hchat.server.domain.ConversationMember;
import com.hchat.server.service.MessageService;
import com.hchat.server.service.PresenceService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * WS 协议 v1 入口。
 *
 * <p>连接后 5 秒内必须发送 {@code auth} 帧（或在 URL 上带 {@code ?token=}），否则断开。
 * 支持 {@code ping/pong} 心跳，断线重连后客户端用 REST {@code after} 游标补拉消息。
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private static final String ATTR_PRINCIPAL = "hchat.principal";
    private static final String ATTR_AUTH_TIMER = "hchat.authTimer";

    private final WsSessionRegistry registry;
    private final WsSender sender;
    private final JwtService jwtService;
    private final MessageService messages;
    private final PresenceService presence;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "hchat-ws-auth-timeout");
        thread.setDaemon(true);
        return thread;
    });

    public ChatWebSocketHandler(WsSessionRegistry registry, WsSender sender, JwtService jwtService,
                                MessageService messages, PresenceService presence, ObjectMapper mapper) {
        this.registry = registry;
        this.sender = sender;
        this.jwtService = jwtService;
        this.messages = messages;
        this.presence = presence;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        registry.register(session);
        String token = queryToken(session);
        if (token != null) {
            authenticate(session, token);
            return;
        }
        ScheduledFuture<?> timer = scheduler.schedule(() -> {
            if (session.getAttributes().get(ATTR_PRINCIPAL) == null) {
                closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            }
        }, 5, TimeUnit.SECONDS);
        session.getAttributes().put(ATTR_AUTH_TIMER, timer);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        JsonNode frame;
        try {
            frame = mapper.readTree(message.getPayload());
        } catch (Exception e) {
            error(session, ErrorCode.INVALID_REQUEST, "无法解析消息帧");
            return;
        }
        String type = frame.path("type").asText("");
        JsonNode payload = frame.path("payload");
        try {
            switch (type) {
                case "auth" -> authenticate(session, payload.path("token").asText(""));
                case "ping" -> sender.send(session, WsEvent.of(WsEvent.PONG,
                        Map.of("ts", Times.now().toEpochMilli())));
                case "message.send" -> onSend(session, payload);
                case "message.read" -> onRead(session, payload);
                case "typing" -> onTyping(session, payload);
                default -> error(session, ErrorCode.INVALID_REQUEST, "未知事件类型: " + type);
            }
        } catch (ApiException e) {
            error(session, e.errorCode(), e.getMessage());
        } catch (Exception e) {
            log.warn("处理 WS 帧失败 type={}: {}", type, e.getMessage());
            error(session, ErrorCode.INTERNAL_ERROR, "处理失败");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        cancelTimer(session);
        ChatPrincipal principal = principal(session);
        registry.unregister(session.getId());
        if (principal != null && !registry.isOnline(principal.userId())) {
            presence.notifyOffline(principal.appId(), principal.userId());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("WS 传输错误 session={}: {}", session.getId(), exception.getMessage());
    }

    private void authenticate(WebSocketSession session, String token) {
        if (token == null || token.isBlank()) {
            error(session, ErrorCode.UNAUTHORIZED, "缺少 token");
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        ChatPrincipal principal;
        try {
            principal = jwtService.parse(token);
        } catch (ApiException e) {
            error(session, ErrorCode.UNAUTHORIZED, e.getMessage());
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        session.getAttributes().put(ATTR_PRINCIPAL, principal);
        cancelTimer(session);
        registry.bind(session.getId(), principal.userId());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", String.valueOf(principal.userId()));
        payload.put("appId", principal.appId());
        payload.put("tenantId", principal.tenantId());
        payload.put("externalUserId", principal.externalUserId());
        payload.put("scopes", new ArrayList<>(principal.scopes()));
        sender.send(session, WsEvent.of(WsEvent.AUTH_OK, payload));
        presence.notifyOnline(principal.appId(), principal.userId());
    }

    private void onSend(WebSocketSession session, JsonNode payload) throws Exception {
        ChatPrincipal principal = requirePrincipal(session);
        long conversationId = payload.path("conversationId").asLong();
        String type = payload.hasNonNull("type") ? payload.path("type").asText() : "text";
        String clientMsgId = payload.hasNonNull("clientMsgId") ? payload.path("clientMsgId").asText() : null;
        JsonNode contentNode = payload.path("content");
        Map<String, Object> content = contentNode.isObject()
                ? mapper.convertValue(contentNode, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {})
                : Map.of();
        // 落库 + 广播（message.new 会推给所有成员，包括发送者的其他端）
        messages.send(principal, conversationId, new MessageDtos.Send(clientMsgId, type, content));
    }

    private void onRead(WebSocketSession session, JsonNode payload) {
        ChatPrincipal principal = requirePrincipal(session);
        long conversationId = payload.path("conversationId").asLong();
        Long messageId = payload.hasNonNull("messageId") ? payload.path("messageId").asLong() : null;
        messages.markRead(principal, conversationId, messageId);
    }

    private void onTyping(WebSocketSession session, JsonNode payload) {
        ChatPrincipal principal = requirePrincipal(session);
        long conversationId = payload.path("conversationId").asLong();
        boolean isTyping = payload.path("isTyping").asBoolean(false);
        Conversation conversation = messages.requireConversation(principal, conversationId);
        messages.requireMember(conversation, principal.userId());
        List<ConversationMember> members = messages.membersOf(conversation);
        List<Long> others = members.stream()
                .map(ConversationMember::getUserId)
                .filter(id -> !id.equals(principal.userId()))
                .toList();
        if (others.isEmpty()) {
            return;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("conversationId", String.valueOf(conversationId));
        event.put("userId", String.valueOf(principal.userId()));
        event.put("isTyping", isTyping);
        presenceBroadcast(others, WsEvent.of(WsEvent.TYPING, event));
    }

    private void presenceBroadcast(List<Long> userIds, WsEvent event) {
        for (Long userId : userIds) {
            for (String sessionId : registry.sessionIdsOf(userId)) {
                WsSessionRegistry.Entry entry = registry.get(sessionId);
                if (entry != null) {
                    sender.send(entry.session(), event);
                }
            }
        }
    }

    private ChatPrincipal requirePrincipal(WebSocketSession session) {
        ChatPrincipal principal = principal(session);
        if (principal == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "请先发送 auth 帧");
        }
        return principal;
    }

    private ChatPrincipal principal(WebSocketSession session) {
        Object value = session.getAttributes().get(ATTR_PRINCIPAL);
        return value instanceof ChatPrincipal p ? p : null;
    }

    private void error(WebSocketSession session, ErrorCode code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code.code());
        payload.put("message", message == null ? code.message() : message);
        sender.send(session, WsEvent.of(WsEvent.ERROR, payload));
    }

    private void cancelTimer(WebSocketSession session) {
        Object timer = session.getAttributes().remove(ATTR_AUTH_TIMER);
        if (timer instanceof ScheduledFuture<?> future) {
            future.cancel(false);
        }
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception e) {
            log.debug("关闭 WS 失败: {}", e.getMessage());
        }
    }

    private String queryToken(WebSocketSession session) {
        if (session.getUri() == null || session.getUri().getQuery() == null) {
            return null;
        }
        for (String pair : session.getUri().getQuery().split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && "token".equals(pair.substring(0, eq))) {
                try {
                    return java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }
}
