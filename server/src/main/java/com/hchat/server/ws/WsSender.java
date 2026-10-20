package com.hchat.server.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/** 统一往 WS 会话写帧：序列化 + 会话级串行化（WebSocketSession 非线程安全）。 */
@Component
public class WsSender {

    private static final Logger log = LoggerFactory.getLogger(WsSender.class);

    private final ObjectMapper objectMapper;

    public WsSender(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void send(WebSocketSession session, WsEvent event) {
        if (session == null || !session.isOpen()) {
            return;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.warn("序列化 WS 事件失败: {}", e.getOriginalMessage());
            return;
        }
        synchronized (session) {
            try {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            } catch (IOException | IllegalStateException e) {
                log.debug("发送 WS 帧失败 session={}: {}", session.getId(), e.getMessage());
            }
        }
    }

    public String toJson(WsEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
