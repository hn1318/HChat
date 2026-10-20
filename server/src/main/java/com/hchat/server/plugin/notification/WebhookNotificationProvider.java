package com.hchat.server.plugin.notification;

import com.hchat.server.service.WebhookService;
import org.springframework.stereotype.Component;

/** 默认通知实现：把事件转成 Webhook 投递。 */
@Component
public class WebhookNotificationProvider implements NotificationProvider {

    private final WebhookService webhookService;

    public WebhookNotificationProvider(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @Override
    public String name() {
        return "webhook";
    }

    @Override
    public void send(NotificationEvent event) {
        webhookService.publish(event.tenantId(), event.appId(), event.type(), event.payload());
    }
}
