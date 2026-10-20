package com.hchat.server.plugin.notification;

/**
 * 通知插件接口。首期实现为 Webhook，可替换为短信 / 邮件 / 推送。
 *
 * <p>实现类会由 {@link com.hchat.server.service.NotificationService} 统一调度。
 */
public interface NotificationProvider {

    String name();

    void send(NotificationEvent event);
}
