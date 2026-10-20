package com.hchat.server.service;

import com.hchat.server.api.dto.ConversationDtos;
import com.hchat.server.api.dto.MessageDtos;
import com.hchat.server.common.JsonCodec;
import com.hchat.server.domain.Conversation;
import com.hchat.server.domain.ConversationMember;
import com.hchat.server.domain.ExternalUser;
import com.hchat.server.domain.Message;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 实体 → API DTO 的转换（ID 一律转字符串，时间转 ISO-8601）。 */
@Component
public class ViewAssembler {

    private final JsonCodec json;

    public ViewAssembler(JsonCodec json) {
        this.json = json;
    }

    public MessageDtos.View message(Message m) {
        return new MessageDtos.View(
                String.valueOf(m.getId()),
                String.valueOf(m.getConversationId()),
                m.getSenderType(),
                m.getSenderId() == null ? null : String.valueOf(m.getSenderId()),
                m.getType(),
                json.readMap(m.getContentJson()),
                m.getClientMsgId(),
                m.getStatus(),
                m.getCreatedAt() == null ? null : m.getCreatedAt().toString());
    }

    public ConversationDtos.View conversation(Conversation c, int unreadCount,
                                              List<ConversationDtos.MemberView> members) {
        return new ConversationDtos.View(
                String.valueOf(c.getId()),
                c.getType(),
                c.getTitle(),
                json.readMap(c.getMetadataJson()),
                c.getLastMessageId() == null ? null : String.valueOf(c.getLastMessageId()),
                c.getLastMessageAt() == null ? null : c.getLastMessageAt().toString(),
                c.getLastMessagePreview(),
                unreadCount,
                members,
                c.getCreatedAt() == null ? null : c.getCreatedAt().toString(),
                c.getUpdatedAt() == null ? null : c.getUpdatedAt().toString());
    }

    public ConversationDtos.MemberView member(ConversationMember m, ExternalUser user) {
        return new ConversationDtos.MemberView(
                String.valueOf(m.getUserId()),
                user == null ? null : user.getExternalUserId(),
                user == null ? null : user.getDisplayName(),
                user == null ? null : user.getAvatarUrl(),
                m.getRole(),
                m.getLastReadMessageId() == null ? null : String.valueOf(m.getLastReadMessageId()),
                m.getUnreadCount() == null ? 0 : m.getUnreadCount());
    }

    public String messagePreview(Message m) {
        return preview(m.getType(), json.readMap(m.getContentJson()));
    }

    /** 会话列表里的最后一条消息摘要。 */
    public static String preview(String type, Map<String, Object> content) {
        if (content == null) {
            return "";
        }
        Object text = content.get("text");
        String result = switch (type == null ? "text" : type) {
            case "image" -> "[图片]";
            case "file" -> "[文件] " + asString(content.get("fileName"));
            case "card" -> "[卡片] " + asString(content.get("title"));
            case "system" -> text == null ? "[系统消息]" : String.valueOf(text);
            default -> asString(text);
        };
        return truncate(result, 200);
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        String flat = value.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max);
    }
}
