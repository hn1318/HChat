package com.hchat.server.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/** 消息 / 分页相关 DTO。 */
public final class MessageDtos {

    private MessageDtos() {}

    public record Send(
            @Size(max = 64) String clientMsgId,
            @Pattern(regexp = "text|image|file|card|system", message = "type 仅支持 text/image/file/card/system")
            String type,
            @NotNull Map<String, Object> content) {}

    public record View(
            String id,
            String conversationId,
            String senderType,
            String senderId,
            String type,
            Map<String, Object> content,
            String clientMsgId,
            String status,
            String createdAt) {}

    public record ReadRequest(@NotNull Long messageId) {}

    public record Page<T>(List<T> items, String nextCursor, boolean hasMore) {}
}
