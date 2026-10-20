package com.hchat.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hchat.server.auth.ChatPrincipal;
import com.hchat.server.common.IdGenerator;
import com.hchat.server.common.Times;
import com.hchat.server.common.JsonCodec;
import com.hchat.server.domain.Message;
import com.hchat.server.mapper.MessageMapper;
import com.hchat.server.plugin.storage.StorageProvider;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 冷热分离：把指定时间之前的历史消息导出为 JSONL，落到对象存储。
 * 首期只做导出，不做物理删除，避免误删数据。
 */
@Service
public class ArchiveService {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    private final MessageMapper messageMapper;
    private final StorageProvider storage;
    private final JsonCodec json;
    private final IdGenerator ids;

    public ArchiveService(MessageMapper messageMapper, StorageProvider storage, JsonCodec json,
                          IdGenerator ids) {
        this.messageMapper = messageMapper;
        this.storage = storage;
        this.json = json;
        this.ids = ids;
    }

    public Map<String, Object> archive(ChatPrincipal principal, Instant before, Long conversationId, int limit) {
        int size = Math.max(1, Math.min(limit, 50_000));
        LambdaQueryWrapper<Message> query = new LambdaQueryWrapper<Message>()
                .eq(Message::getTenantId, principal.tenantId())
                .eq(Message::getAppId, principal.appId())
                .lt(Message::getCreatedAt, before)
                .orderByAsc(Message::getId)
                .last("LIMIT " + size);
        if (conversationId != null) {
            query.eq(Message::getConversationId, conversationId);
        }
        List<Message> rows = messageMapper.selectList(query);
        StringBuilder jsonl = new StringBuilder();
        List<Message> exported = new ArrayList<>(rows.size());
        for (Message message : rows) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("id", String.valueOf(message.getId()));
            line.put("conversationId", String.valueOf(message.getConversationId()));
            line.put("senderType", message.getSenderType());
            line.put("senderId", message.getSenderId() == null ? null : String.valueOf(message.getSenderId()));
            line.put("type", message.getType());
            line.put("content", json.readMap(message.getContentJson()));
            line.put("status", message.getStatus());
            line.put("createdAt", message.getCreatedAt() == null ? null : message.getCreatedAt().toString());
            jsonl.append(json.write(line)).append('\n');
            exported.add(message);
        }
        String key = principal.tenantId() + "/" + principal.appId() + "/archive/"
                + STAMP.format(Times.now()) + "-" + UUID.randomUUID().toString().substring(0, 8) + ".jsonl";
        StorageProvider.StoredObject stored = storage.put(key, jsonl.toString().getBytes(StandardCharsets.UTF_8),
                "application/x-ndjson");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("storageKey", stored.key());
        result.put("url", stored.url());
        result.put("count", exported.size());
        result.put("before", before.toString());
        result.put("bytes", stored.size());
        if (!exported.isEmpty()) {
            result.put("firstMessageId", String.valueOf(exported.get(0).getId()));
            result.put("lastMessageId", String.valueOf(exported.get(exported.size() - 1).getId()));
        }
        result.put("archiveId", String.valueOf(ids.nextId()));
        return result;
    }
}
