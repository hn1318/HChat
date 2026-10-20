package com.hchat.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hchat.server.api.dto.ConversationDtos;
import com.hchat.server.api.dto.MessageDtos;
import com.hchat.server.auth.ChatPrincipal;
import com.hchat.server.common.ApiException;
import com.hchat.server.common.Cursors;
import com.hchat.server.common.ErrorCode;
import com.hchat.server.common.IdGenerator;
import com.hchat.server.common.Times;
import com.hchat.server.common.JsonCodec;
import com.hchat.server.config.HChatProperties;
import com.hchat.server.domain.AiAgent;
import com.hchat.server.domain.Conversation;
import com.hchat.server.domain.ConversationMember;
import com.hchat.server.domain.ExternalUser;
import com.hchat.server.mapper.AiAgentMapper;
import com.hchat.server.mapper.ConversationMapper;
import com.hchat.server.mapper.ConversationMemberMapper;
import com.hchat.server.ws.WsBroadcaster;
import com.hchat.server.ws.WsEvent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 会话服务：创建（含去重）、列表、详情、加成员。 */
@Service
public class ConversationService {

    private final ConversationMapper conversationMapper;
    private final ConversationMemberMapper memberMapper;
    private final AiAgentMapper agentMapper;
    private final UserDirectoryService users;
    private final IdGenerator ids;
    private final JsonCodec json;
    private final HChatProperties props;
    private final ViewAssembler views;
    private final WsBroadcaster broadcaster;
    private final NotificationService notifications;
    private final AuditService audit;

    public ConversationService(ConversationMapper conversationMapper, ConversationMemberMapper memberMapper,
                               AiAgentMapper agentMapper, UserDirectoryService users, IdGenerator ids,
                               JsonCodec json, HChatProperties props, ViewAssembler views,
                               WsBroadcaster broadcaster, NotificationService notifications, AuditService audit) {
        this.conversationMapper = conversationMapper;
        this.memberMapper = memberMapper;
        this.agentMapper = agentMapper;
        this.users = users;
        this.ids = ids;
        this.json = json;
        this.props = props;
        this.views = views;
        this.broadcaster = broadcaster;
        this.notifications = notifications;
        this.audit = audit;
    }

    @Transactional
    public ConversationDtos.View create(ChatPrincipal principal, ConversationDtos.Create request) {
        String type = request.type() == null || request.type().isBlank() ? "c2c" : request.type();
        String dedupeKey = blankToNull(request.dedupeKey());
        if (dedupeKey != null) {
            Conversation existing = findByDedupeKey(principal.appId(), dedupeKey);
            if (existing != null) {
                addMemberIfAbsent(existing, principal.userId(), "c2c".equals(existing.getType()) ? "buyer" : "member");
                return get(principal, existing.getId());
            }
        }
        Long agentId = validateAgent(principal, type, request.agentId());

        Instant now = Times.now();
        Conversation conversation = new Conversation();
        conversation.setId(ids.nextId());
        conversation.setTenantId(principal.tenantId());
        conversation.setAppId(principal.appId());
        conversation.setType(type);
        conversation.setTitle(request.title());
        conversation.setCreatedBy(principal.userId());
        Map<String, Object> metadata = request.metadata() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(request.metadata());
        if (agentId != null) {
            metadata.putIfAbsent("agentId", String.valueOf(agentId));
        }
        conversation.setMetadataJson(json.write(metadata));
        conversation.setDedupeKey(dedupeKey);
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);

        try {
            conversationMapper.insert(conversation);
        } catch (DuplicateKeyException race) {
            // 并发建同一 dedupeKey：回退到已存在会话
            Conversation existing = findByDedupeKey(principal.appId(), dedupeKey);
            if (existing == null) {
                throw race;
            }
            addMemberIfAbsent(existing, principal.userId(), "member");
            return get(principal, existing.getId());
        }

        addMemberIfAbsent(conversation, principal.userId(), "c2c".equals(type) ? "buyer" : "member");
        if (request.members() != null) {
            for (ConversationDtos.MemberInput member : request.members()) {
                ExternalUser user = users.resolve(principal.tenantId(), principal.appId(),
                        member.externalUserId(), member.displayName(), null, Map.of());
                if (!user.getId().equals(principal.userId())) {
                    addMemberIfAbsent(conversation, user.getId(),
                            member.role() == null ? defaultRole(type) : member.role());
                }
            }
        }

        ConversationDtos.View view = get(principal, conversation.getId());
        broadcastUpdated(conversation);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversation", view);
        notifications.publish(conversation.getTenantId(), conversation.getAppId(), "conversation.created", payload);
        audit.log(principal.tenantId(), principal.appId(), principal.userId(), "conversation.create",
                "conversation", String.valueOf(conversation.getId()), Map.of("type", type));
        return view;
    }

    public MessageDtos.Page<ConversationDtos.View> list(ChatPrincipal principal, String cursor, Integer limit) {
        int size = clampPageSize(limit);
        Cursors.Decoded decoded = Cursors.decode(cursor);
        List<Conversation> rows = conversationMapper.listForUser(principal.tenantId(), principal.appId(),
                principal.userId(),
                decoded == null ? null : decoded.time(),
                decoded == null ? null : decoded.id(),
                size + 1);
        boolean hasMore = rows.size() > size;
        if (hasMore) {
            rows = rows.subList(0, size);
        }
        Map<Long, Integer> unread = unreadOf(principal, rows);
        List<ConversationDtos.View> items = rows.stream()
                .map(c -> views.conversation(c, unread.getOrDefault(c.getId(), 0), null))
                .toList();
        String nextCursor = null;
        if (!rows.isEmpty()) {
            Conversation last = rows.get(rows.size() - 1);
            nextCursor = Cursors.encode(last.getLastMessageAt() != null ? last.getLastMessageAt() : last.getCreatedAt(),
                    last.getId());
        }
        return new MessageDtos.Page<>(items, nextCursor, hasMore);
    }

    public ConversationDtos.View get(ChatPrincipal principal, long conversationId) {
        Conversation conversation = requireConversation(principal, conversationId);
        ConversationMember self = memberOf(conversationId, principal.userId());
        if (self == null) {
            throw new ApiException(ErrorCode.NOT_A_MEMBER);
        }
        List<ConversationMember> members = memberMapper.listByConversation(
                conversation.getTenantId(), conversation.getAppId(), conversationId);
        Map<Long, ExternalUser> userMap = users.mapByIds(members.stream().map(ConversationMember::getUserId).toList());
        List<ConversationDtos.MemberView> memberViews = members.stream()
                .map(m -> views.member(m, userMap.get(m.getUserId())))
                .toList();
        return views.conversation(conversation, self.getUnreadCount() == null ? 0 : self.getUnreadCount(), memberViews);
    }

    @Transactional
    public ConversationDtos.View addMember(ChatPrincipal principal, long conversationId,
                                           ConversationDtos.AddMember request) {
        Conversation conversation = requireConversation(principal, conversationId);
        if (memberOf(conversationId, principal.userId()) == null) {
            throw new ApiException(ErrorCode.NOT_A_MEMBER);
        }
        ExternalUser user = users.resolve(principal.tenantId(), principal.appId(),
                request.externalUserId(), null, null, Map.of());
        if (memberOf(conversationId, user.getId()) != null) {
            throw new ApiException(ErrorCode.MEMBER_EXISTS);
        }
        addMemberIfAbsent(conversation, user.getId(),
                request.role() == null ? defaultRole(conversation.getType()) : request.role());
        broadcastUpdated(conversation);
        return get(principal, conversationId);
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

    public ConversationMember memberOf(long conversationId, Long userId) {
        return memberMapper.selectOne(new LambdaQueryWrapper<ConversationMember>()
                .eq(ConversationMember::getConversationId, conversationId)
                .eq(ConversationMember::getUserId, userId)
                .last("LIMIT 1"));
    }

    private void addMemberIfAbsent(Conversation conversation, Long userId, String role) {
        if (memberOf(conversation.getId(), userId) != null) {
            return;
        }
        ConversationMember member = new ConversationMember();
        member.setId(ids.nextId());
        member.setTenantId(conversation.getTenantId());
        member.setAppId(conversation.getAppId());
        member.setConversationId(conversation.getId());
        member.setUserId(userId);
        member.setRole(role);
        member.setJoinedAt(Times.now());
        member.setUnreadCount(0);
        try {
            memberMapper.insert(member);
        } catch (DuplicateKeyException ignored) {
            // 并发加成员，唯一索引兜底
        }
    }

    private Conversation findByDedupeKey(String appId, String dedupeKey) {
        if (dedupeKey == null) {
            return null;
        }
        return conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getAppId, appId)
                .eq(Conversation::getDedupeKey, dedupeKey)
                .last("LIMIT 1"));
    }

    private Long validateAgent(ChatPrincipal principal, String type, Long agentId) {
        if (!"c2ai".equals(type)) {
            return null;
        }
        AiAgent agent = agentId == null ? null : agentMapper.selectById(agentId);
        if (agentId != null && (agent == null || !agent.getAppId().equals(principal.appId()))) {
            throw new ApiException(ErrorCode.AI_AGENT_NOT_FOUND);
        }
        if (agentId == null) {
            AiAgent fallback = agentMapper.selectOne(new LambdaQueryWrapper<AiAgent>()
                    .eq(AiAgent::getAppId, principal.appId())
                    .eq(AiAgent::getStatus, "active")
                    .orderByAsc(AiAgent::getCreatedAt)
                    .last("LIMIT 1"));
            agent = fallback;
        }
        return agent == null ? null : agent.getId();
    }

    private Map<Long, Integer> unreadOf(ChatPrincipal principal, List<Conversation> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        List<Long> conversationIds = rows.stream().map(Conversation::getId).toList();
        List<ConversationMember> memberRows = memberMapper.selectList(new LambdaQueryWrapper<ConversationMember>()
                .eq(ConversationMember::getUserId, principal.userId())
                .in(ConversationMember::getConversationId, conversationIds));
        return memberRows.stream().collect(Collectors.toMap(ConversationMember::getConversationId,
                m -> m.getUnreadCount() == null ? 0 : m.getUnreadCount(), (a, b) -> a));
    }

    private void broadcastUpdated(Conversation conversation) {
        List<ConversationMember> members = memberMapper.listByConversation(
                conversation.getTenantId(), conversation.getAppId(), conversation.getId());
        List<Long> userIds = members.stream().map(ConversationMember::getUserId).toList();
        if (userIds.isEmpty()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversation", views.conversation(conversation, 0, null));
        broadcaster.sendToUsers(userIds, WsEvent.of(WsEvent.CONVERSATION_UPDATED, payload));
    }

    private int clampPageSize(Integer limit) {
        int fallback = props.getLimits().getDefaultPageSize();
        if (limit == null || limit <= 0) {
            return fallback;
        }
        return Math.min(limit, props.getLimits().getMaxPageSize());
    }

    private static String defaultRole(String type) {
        return "c2c".equals(type) ? "seller" : "member";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
