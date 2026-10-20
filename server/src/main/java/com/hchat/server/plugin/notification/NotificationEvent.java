package com.hchat.server.plugin.notification;

import java.util.Map;

/** 通知事件：离线提醒、AI 完成等需要业务方感知的事件。 */
public record NotificationEvent(String tenantId, String appId, String type, Map<String, Object> payload) {}
