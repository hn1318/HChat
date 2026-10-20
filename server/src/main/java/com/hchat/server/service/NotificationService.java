package com.hchat.server.service;

import com.hchat.server.plugin.notification.NotificationEvent;
import com.hchat.server.plugin.notification.NotificationProvider;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 通知调度：把所有事件交给已注册的 {@link NotificationProvider}。
 * 单个 provider 失败不影响其他 provider，也不影响主业务。
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final List<NotificationProvider> providers;

    public NotificationService(List<NotificationProvider> providers) {
        this.providers = providers;
    }

    public void publish(String tenantId, String appId, String type, Map<String, Object> payload) {
        if (providers.isEmpty()) {
            return;
        }
        NotificationEvent event = new NotificationEvent(tenantId, appId, type, payload);
        for (NotificationProvider provider : providers) {
            try {
                provider.send(event);
            } catch (Exception e) {
                log.warn("通知插件 {} 处理 {} 失败: {}", provider.name(), type, e.getMessage());
            }
        }
    }
}
