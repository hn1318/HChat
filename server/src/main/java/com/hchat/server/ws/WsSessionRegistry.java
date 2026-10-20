package com.hchat.server.ws;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * 进程内 WS 会话登记表：sessionId → 会话，userId → sessionIds。
 * 未完成 auth 的会话 user 为 null，只按 sessionId 登记。
 */
@Component
public class WsSessionRegistry {

    public static final class Entry {
        private final WebSocketSession session;
        private volatile Long userId;

        Entry(WebSocketSession session) {
            this.session = session;
        }

        public WebSocketSession session() {
            return session;
        }

        public Long userId() {
            return userId;
        }
    }

    private final ConcurrentHashMap<String, Entry> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Set<String>> byUser = new ConcurrentHashMap<>();

    public void register(WebSocketSession session) {
        sessions.put(session.getId(), new Entry(session));
    }

    public void bind(String sessionId, Long userId) {
        Entry entry = sessions.get(sessionId);
        if (entry == null) {
            return;
        }
        entry.userId = userId;
        byUser.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(sessionId);
    }

    public void unregister(String sessionId) {
        Entry entry = sessions.remove(sessionId);
        if (entry == null || entry.userId == null) {
            return;
        }
        Set<String> set = byUser.get(entry.userId);
        if (set != null) {
            set.remove(sessionId);
            if (set.isEmpty()) {
                byUser.remove(entry.userId, set);
            }
        }
    }

    public Set<String> sessionIdsOf(Long userId) {
        Set<String> set = byUser.get(userId);
        return set == null ? Set.of() : Set.copyOf(set);
    }

    public Entry get(String sessionId) {
        return sessions.get(sessionId);
    }

    public boolean isOnline(Long userId) {
        return byUser.containsKey(userId);
    }

    public int sessionCount() {
        return sessions.size();
    }

    public Set<Long> onlineUserIds() {
        return Set.copyOf(byUser.keySet());
    }
}
