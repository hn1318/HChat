package com.hchat.server.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 多实例广播：事件发到 Redis 频道，各实例只投递给自己持有的会话。
 */
public class RedisWsBroadcaster implements WsBroadcaster, MessageListener {

    public static final String CHANNEL = "hchat:ws:events";

    private static final Logger log = LoggerFactory.getLogger(RedisWsBroadcaster.class);

    private final StringRedisTemplate redis;
    private final WsSessionRegistry registry;
    private final WsSender sender;
    private final ObjectMapper mapper;

    public RedisWsBroadcaster(StringRedisTemplate redis, WsSessionRegistry registry, WsSender sender,
                              ObjectMapper mapper) {
        this.redis = redis;
        this.registry = registry;
        this.sender = sender;
        this.mapper = mapper;
    }

    @Override
    public void sendToUsers(Collection<Long> userIds, WsEvent event) {
        if (userIds == null || userIds.isEmpty() || event == null) {
            return;
        }
        try {
            ObjectNode envelope = mapper.createObjectNode();
            ArrayNode ids = envelope.putArray("userIds");
            userIds.forEach(ids::add);
            envelope.set("event", mapper.valueToTree(event));
            redis.convertAndSend(CHANNEL, mapper.writeValueAsString(envelope));
        } catch (Exception e) {
            log.warn("Redis 广播失败，降级为本地投递: {}", e.getMessage());
            deliverLocally(List.copyOf(userIds), event);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            JsonNode node = mapper.readTree(body);
            List<Long> userIds = new ArrayList<>();
            for (JsonNode id : node.path("userIds")) {
                userIds.add(id.asLong());
            }
            WsEvent event = mapper.treeToValue(node.path("event"), WsEvent.class);
            deliverLocally(userIds, event);
        } catch (Exception e) {
            log.warn("解析 Redis 广播消息失败: {}", e.getMessage());
        }
    }

    private void deliverLocally(List<Long> userIds, WsEvent event) {
        for (Long userId : userIds) {
            for (String sessionId : registry.sessionIdsOf(userId)) {
                WsSessionRegistry.Entry entry = registry.get(sessionId);
                if (entry != null) {
                    sender.send(entry.session(), event);
                }
            }
        }
    }
}
