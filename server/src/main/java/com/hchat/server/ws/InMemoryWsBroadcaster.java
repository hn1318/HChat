package com.hchat.server.ws;

import java.util.Collection;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 单机广播：直接遍历本进程的 WS 会话。 */
@Component
@ConditionalOnProperty(name = "hchat.redis.enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryWsBroadcaster implements WsBroadcaster {

    private final WsSessionRegistry registry;
    private final WsSender sender;

    public InMemoryWsBroadcaster(WsSessionRegistry registry, WsSender sender) {
        this.registry = registry;
        this.sender = sender;
    }

    @Override
    public void sendToUsers(Collection<Long> userIds, WsEvent event) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
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
