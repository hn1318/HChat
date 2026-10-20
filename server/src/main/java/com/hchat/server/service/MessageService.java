package com.hchat.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hchat.server.api.dto.MessageDtos;
import com.hchat.server.auth.ChatPrincipal;
import com.hchat.server.common.ApiException;
import com.hchat.server.common.ErrorCode;
import com.hchat.server.common.IdGenerator;
import com.hchat.server.common.Times;
import com.hchat.server.common.JsonCodec;
import com.hchat.server.config.HChatProperties;
import com.hchat.server.domain.Conversation;
import com.hchat.server.domain.ConversationMember;
import com.hchat.server.domain.Message;
import com.hchat.server.domain.ReadReceipt;
import com.hchat.server.mapper.ConversationMapper;
import com.hchat.server.mapper.ConversationMemberMapper;
import com.hchat.server.mapper.MessageMapper;
import com.hchat.server.mapper.ReadReceiptMapper;
import com.hchat.server.ws.WsBroadcaster;
import com.hchat.server.ws.WsEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 消息服务：发送（幂等）、拉取（游标双向）、已读、撤回。
 *
 * <p>所有查询都强制带上 tenantId + appId，配合成员校验实现多租户隔离。
 */
@Service
public class MessageService {

    private final MessageMapper messageMapper;
    private final ConversationMapper conversationMapper;
    private final ConversationMemberMapper memberMapper;
    private final ReadReceiptMapper readReceiptMapper;
    private final IdGenerator ids;
    private final JsonCodec json;
    private final HChatProperties props;
    private final ViewAssembler views;
    private final WsBroadcaster broadcaster;
    private final NotificationService notifications;

    public MessageService(MessageMapper messageMapper, ConversationMapper conversationMapper,
                          ConversationMemberMapper memberMapper, ReadReceiptMapper readReceiptMapper,
                          IdGenerator ids, JsonCodec json, HChatProperties props, ViewAssembler views,
                          WsBroadcaster broadcaster, NotificationService notifications) {
        this.messageMapper = messageMapper;
        this.conversationMapper = conversationMapper;
        this.memberMapper = memberMapper;
        this.readReceiptMapper = readReceiptMapper;
        this.ids = ids;
        this.json = json;
        this.props = props;
        this.views = views;
        this.broadcaster = broadcaster;
        this.notifications = notifications;
    }

    @Transactional
    public MessageDtos.View send(ChatPrincipal principal, long conversationId, MessageDtos.Send request) {
        Conversation conversation = requireConversation(principal, conversationId);
        requireMember(conversation, principal.userId());
        String type = request.type() == null ? "text" : request.type();
        Map<String, Object> content = request.content() == null ? Map.of() : request.content();
        validateContent(type, content);

        if (request.clientMsgId() != null && !request.clientMsgId().isBlank()) {
            Message existing = messageMapper.selectOne(new LambdaQueryWrapper<Message>()
                    .eq(Message::getConversationId, conversationId)
                    .eq(Message::getClientMsgId, request.clientMsgId())
                    .last("LIMIT 1"));
            if (existing != null) {
                return views.message(existing);
            }
        }
        Message saved = persist(conversation, "user", principal.userId(), type, content, request.clientMsgId(),
                principal);
        return views.message(saved);
    }

    /** AI 回复落库（sender_type=ai，不产生未读，避免刷红点）。 */
    @Transactional
    public MessageDtos.View appendAiMessage(Conversation conversation, Long agentId, String type,
                                            Map<String, Object> content) {
        Message saved = persist(conversation, "ai", agentId, type, content, null, null);
        return views.message(saved);
    }

    /** 系统消息（如「订单已创建」）。 */
    @Transactional
    public MessageDtos.View appendSystemMessage(Conversation conversation, Map<String, Object> content) {
        Message saved = persist(conversation, "system", null, "system", content, null, null);
        return views.message(saved);
    }

    private Message persist(Conversation conversation, String senderType, Long senderId, String type,
                            Map<String, Object> content, String clientMsgId, ChatPrincipal actor) {
        Instant now = Times.now();
        Message message = new Message();
        message.setId(ids.nextId());
        message.setTenantId(conversation.getTenantId());
        message.setAppId(conversation.getAppId());
        message.setConversationId(conversation.getId());
        message.setSenderType(senderType);
        message.setSenderId(senderId);
        message.setType(type);
        message.setContentJson(json.write(content));
        message.setClientMsgId(clientMsgId == null || clientMsgId.isBlank() ? null : clientMsgId);
        message.setStatus("sent");
        message.setCreatedAt(now);
        messageMapper.insert(message);

        String preview = ViewAssembler.preview(type, content);
        conversationMapper.update(null, new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getId, conversation.getId())
                .set(Conversation::getLastMessageId, message.getId())
                .set(Conversation::getLastMessageAt, now)
                .set(Conversation::getLastMessagePreview, preview)
                .set(Conversation::getUpdatedAt, now));
        conversation.setLastMessageId(message.getId());
        conversation.setLastMessageAt(now);
        conversation.setLastMessagePreview(preview);

        List<ConversationMember> members = memberMapper.listByConversation(
                conversation.getTenantId(), conversation.getAppId(), conversation.getId());
        if ("user".equals(senderType)) {
            memberMapper.incrementUnread(conversation.getTenantId(), conversation.getAppId(),
                    conversation.getId(), senderId);
        }

        MessageDtos.View view = views.message(message);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", view);
        broadcaster.sendToUsers(userIds(members), WsEvent.of(WsEvent.MESSAGE_NEW, payload));

        Map<String, Object> webhookPayload = new LinkedHashMap<>();
        webhookPayload.put("conversationId", String.valueOf(conversation.getId()));
        webhookPayload.put("message", view);
        notifications.publish(conversation.getTenantId(), conversation.getAppId(), "message.created", webhookPayload);
        return message;
    }

    /**
     * 拉取消息。无参数=最新一页；{@code before}=向前翻页；{@code after}=断线补拉。
     */
    public MessageDtos.Page<MessageDtos.View> list(ChatPrincipal principal, long conversationId,
                                                   Long before, Long after, Integer limit) {
        Conversation conversation = requireConversation(principal, conversationId);
        requireMember(conversation, principal.userId());
        int size = clampPageSize(limit);

        boolean forward = after != null;
        LambdaQueryWrapper<Message> query = new LambdaQueryWrapper<Message>()
                .eq(Message::getTenantId, conversation.getTenantId())
                .eq(Message::getAppId, conversation.getAppId())
                .eq(Message::getConversationId, conversationId);
        if (forward) {
            query.gt(Message::getId, after).orderByAsc(Message::getId);
        } else {
            if (before != null) {
                query.lt(Message::getId, before);
            }
            query.orderByDesc(Message::getId);
        }
        query.last("LIMIT " + (size + 1));

        List<Message> rows = messageMapper.selectList(query);
        boolean hasMore = rows.size() > size;
        if (hasMore) {
            rows = new ArrayList<>(rows.subList(0, size));
        }
        if (!forward) {
            rows = new ArrayList<>(rows);
            java.util.Collections.reverse(rows);
        }
        List<MessageDtos.View> items = rows.stream().map(views::message).toList();
        String nextCursor = null;
        if (!items.isEmpty()) {
            nextCursor = forward ? items.get(items.size() - 1).id() : items.get(0).id();
        }
        return new MessageDtos.Page<>(items, nextCursor, hasMore);
    }

    @Transactional
    public void markRead(ChatPrincipal principal, long conversationId, Long messageId) {
        Conversation conversation = requireConversation(principal, conversationId);
        requireMember(conversation, principal.userId());
        Long target = messageId != null ? messageId : conversation.getLastMessageId();
        if (target == null) {
            return;
        }
        memberMapper.markRead(conversation.getTenantId(), conversation.getAppId(), conversationId,
                principal.userId(), target);

        ReadReceipt receipt = readReceiptMapper.selectOne(new LambdaQueryWrapper<ReadReceipt>()
                .eq(ReadReceipt::getConversationId, conversationId)
                .eq(ReadReceipt::getUserId, principal.userId())
                .last("LIMIT 1"));
        Instant now = Times.now();
        if (receipt == null) {
            receipt = new ReadReceipt();
            receipt.setId(ids.nextId());
            receipt.setTenantId(conversation.getTenantId());
            receipt.setAppId(conversation.getAppId());
            receipt.setConversationId(conversationId);
            receipt.setUserId(principal.userId());
            receipt.setMessageId(target);
            receipt.setReadAt(now);
            readReceiptMapper.insert(receipt);
        } else {
            receipt.setMessageId(target);
            receipt.setReadAt(now);
            readReceiptMapper.updateById(receipt);
        }

        List<ConversationMember> members = memberMapper.listByConversation(
                conversation.getTenantId(), conversation.getAppId(), conversationId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", String.valueOf(conversationId));
        payload.put("userId", String.valueOf(principal.userId()));
        payload.put("messageId", String.valueOf(target));
        broadcaster.sendToUsers(userIds(members), WsEvent.of(WsEvent.MESSAGE_READ, payload));
    }

    @Transactional
    public MessageDtos.View recall(ChatPrincipal principal, long messageId) {
        Message message = messageMapper.selectById(messageId);
        if (message == null || !message.getAppId().equals(principal.appId())
                || !message.getTenantId().equals(principal.tenantId())) {
            throw new ApiException(ErrorCode.MESSAGE_NOT_FOUND);
        }
        Conversation conversation = requireConversation(principal, message.getConversationId());
        requireMember(conversation, principal.userId());
        if (message.getSenderId() == null || !message.getSenderId().equals(principal.userId())) {
            throw new ApiException(ErrorCode.MESSAGE_NOT_OWNED);
        }
        int window = props.getLimits().getRecallWindowSeconds();
        if (message.getCreatedAt() != null
                && Duration.between(message.getCreatedAt(), Times.now()).getSeconds() > window) {
            throw new ApiException(ErrorCode.RECALL_WINDOW_EXPIRED,
                    "消息发送已超过 " + window + " 秒，无法撤回");
        }
        message.setStatus("recalled");
        messageMapper.updateById(message);
        if (message.getId().equals(conversation.getLastMessageId())) {
            conversationMapper.update(null, new LambdaUpdateWrapper<Conversation>()
                    .eq(Conversation::getId, conversation.getId())
                    .set(Conversation::getLastMessagePreview, "[消息已撤回]"));
        }

        List<ConversationMember> members = memberMapper.listByConversation(
                conversation.getTenantId(), conversation.getAppId(), conversation.getId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", String.valueOf(messageId));
        payload.put("conversationId", String.valueOf(conversation.getId()));
        payload.put("message", views.message(message));
        broadcaster.sendToUsers(userIds(members), WsEvent.of(WsEvent.MESSAGE_RECALLED, payload));
        return views.message(message);
    }

    public List<ConversationMember> membersOf(Conversation conversation) {
        return memberMapper.listByConversation(conversation.getTenantId(), conversation.getAppId(),
                conversation.getId());
    }

    public Conversation requireConversation(ChatPrincipal principal, long conversationId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null
                || !conversation.getAppId().equals(principal.appId())
                || !conversation.getTenantId().equals(principal.tenantId())) {
            throw new ApiException(ErrorCode.CONVERSATION_NOT_FOUND);
        }
        return conversation;
    }

    public void requireMember(Conversation conversation, Long userId) {
        Long count = memberMapper.selectCount(new LambdaQueryWrapper<ConversationMember>()
                .eq(ConversationMember::getConversationId, conversation.getId())
                .eq(ConversationMember::getUserId, userId));
        if (count == null || count == 0) {
            throw new ApiException(ErrorCode.NOT_A_MEMBER);
        }
    }

    private void validateContent(String type, Map<String, Object> content) {
        if ("text".equals(type)) {
            Object text = content.get("text");
            if (text == null || String.valueOf(text).isBlank()) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "text 消息必须包含非空 content.text");
            }
            int max = props.getLimits().getMaxMessageLength();
            if (String.valueOf(text).length() > max) {
                throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "消息长度超过 " + max + " 字符");
            }
        }
        if ("image".equals(type) || "file".equals(type)) {
            if (content.get("url") == null) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, type + " 消息必须包含 content.url");
            }
        }
        if (content.size() > 50) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "content 字段过多");
        }
    }

    private int clampPageSize(Integer limit) {
        int fallback = props.getLimits().getDefaultPageSize();
        if (limit == null || limit <= 0) {
            return fallback;
        }
        return Math.min(limit, props.getLimits().getMaxPageSize());
    }

    private static List<Long> userIds(List<ConversationMember> members) {
        return members.stream().map(ConversationMember::getUserId).toList();
    }
}
