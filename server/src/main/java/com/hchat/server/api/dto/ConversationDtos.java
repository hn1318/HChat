package com.hchat.server.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/** 会话相关 DTO。ID 统一用字符串，避免 JS 侧雪花 ID 精度丢失。 */
public final class ConversationDtos {

    private ConversationDtos() {}

    public record Create(
            @Pattern(regexp = "c2c|c2ai|c2c_ai_assist", message = "type 仅支持 c2c / c2ai / c2c_ai_assist")
            String type,
            @Size(max = 256) String title,
            Map<String, Object> metadata,
            List<MemberInput> members,
            Long agentId,
            @Size(max = 128) String dedupeKey) {}

    public record MemberInput(
            @NotBlank @Size(max = 128) String externalUserId,
            @Pattern(regexp = "buyer|seller|agent|member", message = "role 仅支持 buyer/seller/agent/member")
            String role,
            @Size(max = 128) String displayName) {}

    public record AddMember(
            @NotBlank @Size(max = 128) String externalUserId,
            @Pattern(regexp = "buyer|seller|agent|member", message = "role 仅支持 buyer/seller/agent/member")
            String role) {}

    public record MemberView(
            String userId,
            String externalUserId,
            String displayName,
            String avatarUrl,
            String role,
            String lastReadMessageId,
            int unreadCount) {}

    public record View(
            String id,
            String type,
            String title,
            Map<String, Object> metadata,
            String lastMessageId,
            String lastMessageAt,
            String lastMessagePreview,
            int unreadCount,
            List<MemberView> members,
            String createdAt,
            String updatedAt) {}
}
