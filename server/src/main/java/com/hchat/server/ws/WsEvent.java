package com.hchat.server.ws;

import java.util.Map;

/**
 * 服务端下发的 WS 事件（协议版本 1）。
 *
 * <p>所有事件都带 {@code version}，便于将来做协议演进。
 */
public record WsEvent(int version, String type, Map<String, Object> payload) {

    public static final int VERSION = 1;

    // 服务端事件类型
    public static final String AUTH_OK = "auth.ok";
    public static final String MESSAGE_NEW = "message.new";
    public static final String MESSAGE_READ = "message.read";
    public static final String MESSAGE_RECALLED = "message.recalled";
    public static final String CONVERSATION_UPDATED = "conversation.updated";
    public static final String PRESENCE = "presence";
    public static final String TYPING = "typing";
    public static final String PONG = "pong";
    public static final String ERROR = "error";

    public static WsEvent of(String type, Map<String, Object> payload) {
        return new WsEvent(VERSION, type, payload);
    }
}
