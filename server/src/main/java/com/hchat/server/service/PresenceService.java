package com.hchat.server.service;

import com.hchat.server.config.HChatProperties;
import com.hchat.server.mapper.ConversationMemberMapper;
import com.hchat.server.ws.WsBroadcaster;
import com.hchat.server.ws.WsEvent;
import com.hchat.server.ws.WsSessionRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 在线状态：默认进程内判定；开启 Redis 后用带 TTL 的键，支持多实例。
 * 上下线时向该用户所有会话的对端广播 {@code presence}。
 */
@Service
public class PresenceService {

    private static final Logger log = LoggerFactory.getLogger(PresenceService.class);
    private static final Duration TTL = Duration.ofSeconds(90);

    private final WsSessionRegistry registry;
    private final WsBroadcaster broadcaster;
    private final ConversationMemberMapper memberMapper;
    private final HChatProperties props;
    private final ObjectProvider<StringRedisTemplate> redisProvider;

    public PresenceService(WsSessionRegistry registry, WsBroadcaster broadcaster,
                           ConversationMemberMapper memberMapper, HChatProperties props,
                           ObjectProvider<StringRedisTemplate> redisProvider) {
        this.registry = registry;
        this.broadcaster = broadcaster;
        this.memberMapper = memberMapper;
        this.props = props;
        this.redisProvider = redisProvider;
    }

    @Async
    public void notifyOnline(String appId, Long userId) {
        markOnline(appId, userId);
        broadcastPresence(appId, userId, true);
    }

    @Async
    public void notifyOffline(String appId, Long userId) {
        if (!registry.isOnline(userId)) {
            markOffline(appId, userId);
            broadcastPresence(appId, userId, false);
        }
    }

    /** 心跳续期（Redis 模式）。 */
    public void touch(String appId, Long userId) {
        if (!props.getRedis().isEnabled()) {
            return;
        }
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis != null) {
            try {
                redis.opsForValue().set(key(appId, userId), "1", TTL);
            } catch (Exception e) {
                log.debug("Redis presence 续期失败: {}", e.getMessage());
            }
        }
    }

    public boolean isOnline(String appId, Long userId) {
        if (!props.getRedis().isEnabled()) {
            return registry.isOnline(userId);
        }
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return registry.isOnline(userId);
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(key(appId, userId)));
        } catch (Exception e) {
            return registry.isOnline(userId);
        }
    }

    private void markOnline(String appId, Long userId) {
        if (!props.getRedis().isEnabled()) {
            return;
        }
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis != null) {
            try {
                redis.opsForValue().set(key(appId, userId), "1", TTL);
            } catch (Exception e) {
                log.debug("Redis presence 写入失败: {}", e.getMessage());
            }
        }
    }

    private void markOffline(String appId, Long userId) {
        if (!props.getRedis().isEnabled()) {
            return;
        }
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis != null) {
            try {
                redis.delete(key(appId, userId));
            } catch (Exception e) {
                log.debug("Redis presence 删除失败: {}", e.getMessage());
            }
        }
    }

    private void broadcastPresence(String appId, Long userId, boolean online) {
        try {
            List<Long> peers = memberMapper.findPeers(appId, userId);
            if (peers.isEmpty()) {
                return;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("userId", String.valueOf(userId));
            payload.put("online", online);
            broadcaster.sendToUsers(peers, WsEvent.of(WsEvent.PRESENCE, payload));
        } catch (Exception e) {
            log.debug("广播在线状态失败: {}", e.getMessage());
        }
    }

    private static String key(String appId, Long userId) {
        return "hchat:presence:" + appId + ":" + userId;
    }
}
