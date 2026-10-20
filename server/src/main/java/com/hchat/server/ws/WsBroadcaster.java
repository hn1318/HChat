package com.hchat.server.ws;

import java.util.Collection;

/**
 * WS 事件广播。单机用内存实现；开启 Redis 后走 Pub/Sub，支持多实例水平扩展。
 */
public interface WsBroadcaster {

    void sendToUsers(Collection<Long> userIds, WsEvent event);

    default void sendToUser(Long userId, WsEvent event) {
        sendToUsers(java.util.List.of(userId), event);
    }
}
